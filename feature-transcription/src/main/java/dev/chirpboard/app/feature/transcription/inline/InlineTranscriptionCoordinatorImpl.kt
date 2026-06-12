package dev.chirpboard.app.feature.transcription.inline

import android.util.Log
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionCoordinator
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.transcription.WordReplacer
import dev.chirpboard.app.feature.transcription.audio.ChunkedAudioProcessor
import dev.chirpboard.app.feature.transcription.audio.asSampleFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class InlineTranscriptionCoordinatorImpl
    @Inject
    constructor(
        private val transcriberProvider: TranscriberProvider,
        private val textEnhancement: RecordingTextEnhancementPort,
        private val modelReadinessGate: SpeechModelReadinessGate,
        private val wordReplacementRepository: WordReplacementRepository,
        private val wordReplacer: WordReplacer,
    ) : InlineTranscriptionCoordinator {
        private val tag = "InlineTranscription"

        private val _phase = MutableStateFlow<InlineTranscriptionPhase>(InlineTranscriptionPhase.Idle)
        override val phase: StateFlow<InlineTranscriptionPhase> = _phase.asStateFlow()

        /**
         * Set by the owners of user-initiated cancellation right before they cancel the
         * pipeline job; consumed (and reset) when a [CancellationException] is classified.
         * Cleared at the start of every request so a mark left behind by a cancel that
         * never reached an in-flight pipeline cannot misclassify a later interruption.
         */
        private val userCancelRequested = AtomicBoolean(false)

        override fun resetPhase() {
            _phase.value = InlineTranscriptionPhase.Idle
        }

        override fun setError(message: String) {
            _phase.value = InlineTranscriptionPhase.Error(message)
        }

        override fun markUserCancelled() {
            userCancelRequested.set(true)
        }

        override suspend fun transcribeWithCommitResult(
            request: InlineTranscriptionRequest,
            persistence: InlineCapturePersistence?,
            commitText: (String) -> Boolean,
            onRecordingCompleted: () -> Unit,
            onRecordingError: (String) -> Unit,
        ) {
            var rawTextForPersistence: String? = null
            userCancelRequested.set(false)
            // True once this request's capture reached a terminal persist or discard.
            // A cancellation surfacing after that point (e.g. on leaving a dispatcher
            // boundary right after the committed-path persist) must not persist the
            // same capture a second time.
            val captureResolved = AtomicBoolean(false)

            // Declared before the try so the generic-exception handler reuses the same
            // id rather than minting a fresh one, keeping STARTED paired with its
            // terminal FAILURE for stuck-in-STARTED trace analysis.
            val correlationId = ReliabilityEventLogger.newCorrelationId(request.correlationPrefix)
            // Bound loggers fill in the stage/correlationId and derive the prefix portion of
            // every reason code so each emit below is a one-liner instead of a 5-line block.
            val transcriptionLog =
                ReliabilityEventLogger.scoped(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    correlationId = correlationId,
                    reasonPrefix = request.correlationPrefix,
                )
            val enhancementLog =
                ReliabilityEventLogger.scoped(
                    stage = ReliabilityStage.ENHANCEMENT,
                    correlationId = correlationId,
                    reasonPrefix = request.correlationPrefix,
                )

            try {
                transcriptionLog.started("transcription_started")

                if (!transcriberProvider.isReady()) {
                    _phase.value = InlineTranscriptionPhase.LoadingModel(progress = null)
                    val ready = ensureRecognizerReady()
                    if (!ready) {
                        val message =
                            if (!transcriberProvider.isModelDownloaded()) {
                                "Speech model is not downloaded yet"
                            } else {
                                "Failed to load speech model"
                            }
                        transcriptionLog.failure("recognizer_not_ready")
                        val rescued =
                            rescueCapture(
                                persistence = persistence,
                                audioSource = request.audioSource,
                                rawText = null,
                                errorMessage = message,
                                reason = InlineCapturePersistReason.RESCUE,
                            )
                        captureResolved.set(true)
                        // ERR-25: when the capture was rescued, say so — otherwise users assume
                        // their dictation is gone and re-speak it.
                        val displayMessage = if (rescued) message + RESCUE_SAVED_SUFFIX else message
                        _phase.value = InlineTranscriptionPhase.Error(displayMessage)
                        onRecordingError(displayMessage)
                        return
                    }
                }

                _phase.value = InlineTranscriptionPhase.Transcribing

                val mappedOutcome =
                    try {
                        transcribeAudioSource(request.audioSource)
                    } catch (e: OutOfMemoryError) {
                        // Never let an OOM from assembling large sample arrays crash the IME
                        // process; fall through to the failure path so the audio is rescued.
                        Log.e(tag, "Out of memory while transcribing dictation audio", e)
                        InlineTranscriptionResolution.Failure("Not enough memory to transcribe dictation")
                    }
                val rawText =
                    when (mappedOutcome) {
                        // PLH-10: the user's word-replacement dictionary applies to inline
                        // dictations exactly like recorder-app transcriptions — before the LLM
                        // sees the text, so the raw fallback, the polish input and the persisted
                        // rawText are all post-replacement.
                        is InlineTranscriptionResolution.Success -> applyWordReplacements(mappedOutcome.text)

                        InlineTranscriptionResolution.NoSpeech -> {
                            transcriptionLog.skipped("no_speech")
                            withContext(Dispatchers.Main) {
                                // Discard only this request's audio: when this pipeline was
                                // detached by a stop timeout, discardSamples() could delete a
                                // newer dictation's staged source instead of ours.
                                persistence?.discardAudioSource(request.audioSource)
                                captureResolved.set(true)
                                onRecordingCompleted()
                                _phase.value = InlineTranscriptionPhase.Idle
                            }
                            return
                        }

                        is InlineTranscriptionResolution.Failure -> {
                            transcriptionLog.failure("transcription_failed", message = mappedOutcome.message)
                            val rescued =
                                rescueCapture(
                                    persistence = persistence,
                                    audioSource = request.audioSource,
                                    rawText = rawTextForPersistence,
                                    errorMessage = mappedOutcome.message,
                                    reason = InlineCapturePersistReason.RESCUE,
                                )
                            captureResolved.set(true)
                            val displayMessage =
                                if (rescued) mappedOutcome.message + RESCUE_SAVED_SUFFIX else mappedOutcome.message
                            _phase.value = InlineTranscriptionPhase.Error(displayMessage)
                            onRecordingError(displayMessage)
                            return
                        }
                    }

                // Never log the transcript verbatim: this pipeline runs inside the IME, so
                // the text is whatever the user dictated into another app. Log only its length.
                Log.d(tag, "Transcribed ${rawText.length} chars")
                rawTextForPersistence = rawText
                transcriptionLog.success("transcription_completed")

                if (request.llmEnabled) {
                    enhancementLog.started("enhancement_started")
                    _phase.value = InlineTranscriptionPhase.Polishing

                    val result =
                        withTimeoutOrNull(10_000L) {
                            textEnhancement.process(rawText, request.processingModeId)
                        }

                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            result.fold(
                                onSuccess = { polishedText ->
                                    enhancementLog.success("enhancement_completed")
                                    deliverTranscript(
                                        delivery =
                                            TranscriptDelivery(
                                                request = request,
                                                persistence = persistence,
                                                rawText = rawText,
                                                processedText = polishedText,
                                                phaseOnCommit = InlineTranscriptionPhase.Idle,
                                                correlationId = correlationId,
                                                captureResolved = captureResolved,
                                            ),
                                        commitText = commitText,
                                        onRecordingCompleted = onRecordingCompleted,
                                        onRecordingError = onRecordingError,
                                    )
                                },
                                onFailure = { error ->
                                    enhancementLog.failure("enhancement_failed", error)
                                    deliverTranscript(
                                        delivery =
                                            TranscriptDelivery(
                                                request = request,
                                                persistence = persistence,
                                                rawText = rawText,
                                                processedText = null,
                                                phaseOnCommit =
                                                    InlineTranscriptionPhase.LlmError("LLM failed: ${error.message}"),
                                                correlationId = correlationId,
                                                captureResolved = captureResolved,
                                            ),
                                        commitText = commitText,
                                        onRecordingCompleted = onRecordingCompleted,
                                        onRecordingError = onRecordingError,
                                    )
                                },
                            )
                        } else {
                            enhancementLog.failure("enhancement_timeout")
                            // ERR-19: an enhancement timeout degrades to the raw transcript just
                            // like an enhancement failure, so it surfaces the same LlmError panel
                            // instead of silently returning to Idle.
                            deliverTranscript(
                                delivery =
                                    TranscriptDelivery(
                                        request = request,
                                        persistence = persistence,
                                        rawText = rawText,
                                        processedText = null,
                                        phaseOnCommit =
                                            InlineTranscriptionPhase.LlmError(ENHANCEMENT_TIMEOUT_MESSAGE),
                                        correlationId = correlationId,
                                        captureResolved = captureResolved,
                                    ),
                                commitText = commitText,
                                onRecordingCompleted = onRecordingCompleted,
                                onRecordingError = onRecordingError,
                            )
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        deliverTranscript(
                            delivery =
                                TranscriptDelivery(
                                    request = request,
                                    persistence = persistence,
                                    rawText = rawText,
                                    processedText = null,
                                    phaseOnCommit = InlineTranscriptionPhase.Idle,
                                    correlationId = correlationId,
                                    captureResolved = captureResolved,
                                ),
                            commitText = commitText,
                            onRecordingCompleted = onRecordingCompleted,
                            onRecordingError = onRecordingError,
                        )
                    }
                }
            } catch (e: CancellationException) {
                persistCancelledRequest(
                    request = request,
                    persistence = persistence,
                    rawText = rawTextForPersistence,
                    captureResolved = captureResolved.get(),
                    cause = e,
                )
                throw e
            } catch (e: Exception) {
                val errorMessage = "Transcription failed: ${e.message}"
                transcriptionLog.failure("exception", e)
                val rescued =
                    rescueCapture(
                        persistence = persistence,
                        audioSource = request.audioSource,
                        rawText = rawTextForPersistence,
                        errorMessage = errorMessage,
                        reason = InlineCapturePersistReason.RESCUE,
                    )
                val displayMessage = if (rescued) errorMessage + RESCUE_SAVED_SUFFIX else errorMessage
                _phase.value = InlineTranscriptionPhase.Error(displayMessage)
                onRecordingError(displayMessage)
            }
        }

        /**
         * Applies the user's enabled word replacements to a fresh transcript (PLH-10). Failures
         * (e.g. a DB read error) must never fail the dictation: the unmodified transcript wins.
         */
        private suspend fun applyWordReplacements(text: String): String =
            try {
                val replacements = wordReplacementRepository.getEnabledReplacements()
                if (replacements.isEmpty()) text else wordReplacer.apply(text, replacements)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Word replacements unavailable; using the unmodified transcript", e)
                text
            }

        /**
         * Classifies a pipeline cancellation before rethrowing it. Only an explicit user
         * action marks the request via [markUserCancelled]; that persist respects the
         * save preference. Every other cancellation — IME service destruction, scope
         * death, system kill, task swipe mid-transcription — must rescue the capture
         * regardless of the preference so the user's speech is never silently dropped.
         * A request whose capture already reached a terminal persist or discard is left
         * alone so the rescue cannot duplicate an already-committed capture.
         */
        private suspend fun persistCancelledRequest(
            request: InlineTranscriptionRequest,
            persistence: InlineCapturePersistence?,
            rawText: String?,
            captureResolved: Boolean,
            cause: CancellationException,
        ) {
            // Always consume the mark so it cannot leak into a later request.
            val userCancelled = userCancelRequested.getAndSet(false)
            if (captureResolved) {
                Log.w(tag, "Transcription cancelled after its capture was already persisted or discarded", cause)
                return
            }
            val (errorMessage, reason) =
                if (userCancelled) {
                    "Dictation cancelled" to InlineCapturePersistReason.USER_CANCELLED
                } else {
                    CANCELLATION_RESCUE_MESSAGE to InlineCapturePersistReason.RESCUE
                }
            Log.w(tag, "Transcription cancelled (userInitiated=$userCancelled)", cause)
            rescueCapture(
                persistence = persistence,
                audioSource = request.audioSource,
                rawText = rawText,
                errorMessage = errorMessage,
                reason = reason,
            )
        }

        /**
         * Persists a capture on a failure/cancel/commit-refused path under one consistent
         * policy: the persist runs [NonCancellable] (the surrounding job is usually being
         * cancelled or has already failed) on [Dispatchers.Main] (the persistence layer
         * expects the IME's main thread), and any persist exception is swallowed and logged
         * rather than allowed to escape.
         *
         * Swallowing is deliberate and crash-critical: an exception thrown out of a
         * [NonCancellable] block on these paths would otherwise propagate into the IME
         * process and kill it, dropping the very capture this call exists to save. The
         * caller still owns the phase update and the onRecordingError/onRecordingCompleted
         * callback so each site keeps its own user-visible outcome; this helper only owns
         * the "captured speech is never silently dropped, and a rescue can never crash the
         * process" invariant.
         *
         * @return true when the persist completed, so callers can tell the user their audio was
         * saved (ERR-25); false when there is no persistence or the persist failed.
         */
        private suspend fun rescueCapture(
            persistence: InlineCapturePersistence?,
            audioSource: InlineAudioSource,
            rawText: String?,
            processedText: String? = null,
            errorMessage: String?,
            reason: InlineCapturePersistReason,
        ): Boolean {
            if (persistence == null) return false
            return withContext(NonCancellable + Dispatchers.Main) {
                try {
                    persistence.persistAudioSource(
                        audioSource = audioSource,
                        rawText = rawText,
                        processedText = processedText,
                        errorMessage = errorMessage,
                        reason = reason,
                    )
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never rethrow a real failure: a persist exception here must not escape
                    // into the IME process. The capture's backing audio file remains on disk
                    // for the next reconciliation/recovery pass even if this DB write failed.
                    Log.e(tag, "Rescue persist failed (reason=$reason); capture left for recovery", e)
                    false
                }
            }
        }

        /**
         * Commits the transcript to its target, falling back to a rescue persistence entry
         * when the commit is refused (stale input session or missing input connection) so
         * the user's speech is never silently dropped.
         */
        private suspend fun deliverTranscript(
            delivery: TranscriptDelivery,
            commitText: (String) -> Boolean,
            onRecordingCompleted: () -> Unit,
            onRecordingError: (String) -> Unit,
        ) {
            val textToCommit = delivery.processedText ?: delivery.rawText
            val committed = commitText("$textToCommit ")
            if (committed) {
                // The transcript reached its target; mark the capture resolved BEFORE the
                // (cancellable) persist below so a cancellation surfacing at this
                // dispatcher boundary cannot re-persist the same capture in the
                // CancellationException handler.
                delivery.captureResolved.set(true)
                delivery.persistence?.persistAudioSource(
                    audioSource = delivery.request.audioSource,
                    rawText = delivery.rawText,
                    processedText = delivery.processedText,
                    errorMessage = null,
                    reason = InlineCapturePersistReason.COMPLETED,
                )
                onRecordingCompleted()
                _phase.value = delivery.phaseOnCommit
                return
            }

            Log.w(tag, "Commit refused; persisting transcript as a rescue entry")
            ReliabilityEventLogger
                .scoped(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    correlationId = delivery.correlationId,
                    reasonPrefix = delivery.request.correlationPrefix,
                ).failure("commit_refused")
            rescueCapture(
                persistence = delivery.persistence,
                audioSource = delivery.request.audioSource,
                rawText = delivery.rawText,
                processedText = delivery.processedText,
                errorMessage = COMMIT_REFUSED_MESSAGE,
                reason = InlineCapturePersistReason.RESCUE,
            )
            delivery.captureResolved.set(true)
            onRecordingError(COMMIT_REFUSED_MESSAGE)
            _phase.value = InlineTranscriptionPhase.Error(COMMIT_REFUSED_MESSAGE)
        }

        private suspend fun ensureRecognizerReady(): Boolean {
            if (transcriberProvider.isReady()) {
                return true
            }
            if (!transcriberProvider.isModelDownloaded()) {
                return false
            }
            modelReadinessGate.ensureReady(VerificationTrigger.KEYBOARD_DICTATION)
            return if (transcriberProvider.isReady()) {
                true
            } else {
                transcriberProvider.initialize()
            }
        }

        private suspend fun transcribeAudioSource(audioSource: InlineAudioSource): InlineTranscriptionResolution =
            when (audioSource) {
                is InlineAudioSource.InMemory ->
                    // Long in-memory captures (recognition-surface dictations can reach the
                    // 10-minute recorder cap) go through the same bounded 30s chunking as the
                    // file-backed path: a single multi-minute utterance costs quadratic native
                    // attention memory in sherpa-onnx and risks an unrecoverable native OOM.
                    if (audioSource.samples.size > audioSource.sampleRate * SINGLE_UTTERANCE_MAX_SECONDS) {
                        transcribeChunked(audioSource)
                    } else {
                        mapInlineTranscriptionOutcome(
                            transcriberProvider.transcribe(audioSource.samples, audioSource.sampleRate),
                        )
                    }

                is InlineAudioSource.PcmFloatFile -> transcribeChunked(audioSource)
            }

        private suspend fun transcribeChunked(audioSource: InlineAudioSource): InlineTranscriptionResolution {
            val processor =
                ChunkedAudioProcessor(
                    chunkDurationMs = 30_000,
                    overlapDurationMs = 2_000,
                    sampleRate = audioSource.sampleRate,
                )
            val transcript =
                processor.processAndJoin(audioSource.asSampleFlow()) { samples ->
                    when (
                        val outcome =
                            mapInlineTranscriptionOutcome(
                                transcriberProvider.transcribe(samples, audioSource.sampleRate),
                            )
                    ) {
                        is InlineTranscriptionResolution.Success -> outcome.text
                        InlineTranscriptionResolution.NoSpeech -> ""
                        is InlineTranscriptionResolution.Failure -> throw InlineTranscriptionFailureException(outcome.message)
                    }
                }

            return if (transcript.isBlank()) {
                InlineTranscriptionResolution.NoSpeech
            } else {
                InlineTranscriptionResolution.Success(transcript)
            }
        }
    }

/** In-memory captures longer than this are decoded in bounded chunks instead of one utterance. */
private const val SINGLE_UTTERANCE_MAX_SECONDS = 60

internal const val COMMIT_REFUSED_MESSAGE =
    "Couldn't insert dictated text into the field; transcript saved to recordings"

internal const val CANCELLATION_RESCUE_MESSAGE =
    "Dictation was interrupted before it finished; the capture was saved to recordings"

/** ERR-19: shown when AI polish times out and the raw transcript was inserted instead. */
internal const val ENHANCEMENT_TIMEOUT_MESSAGE =
    "AI processing timed out — inserted the raw transcript"

/** ERR-25: appended to rescue-backed errors so users know their speech was not lost. */
internal const val RESCUE_SAVED_SUFFIX = " — your audio was saved to recordings"

private data class TranscriptDelivery(
    val request: InlineTranscriptionRequest,
    val persistence: InlineCapturePersistence?,
    val rawText: String,
    val processedText: String?,
    val phaseOnCommit: InlineTranscriptionPhase,
    val correlationId: String,
    val captureResolved: AtomicBoolean,
)

private class InlineTranscriptionFailureException(
    message: String,
) : Exception(message)
