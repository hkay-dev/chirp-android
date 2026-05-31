package dev.chirpboard.app.feature.transcription.inline

import android.util.Log
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionCoordinator
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.feature.transcription.audio.ChunkedAudioProcessor
import dev.chirpboard.app.feature.transcription.audio.asSampleFlow
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
    ) : InlineTranscriptionCoordinator {
        private val tag = "InlineTranscription"

        private val _phase = MutableStateFlow<InlineTranscriptionPhase>(InlineTranscriptionPhase.Idle)
        override val phase: StateFlow<InlineTranscriptionPhase> = _phase.asStateFlow()

        override fun resetPhase() {
            _phase.value = InlineTranscriptionPhase.Idle
        }

        override fun setError(message: String) {
            _phase.value = InlineTranscriptionPhase.Error(message)
        }

        override suspend fun transcribe(
            request: InlineTranscriptionRequest,
            persistence: InlineCapturePersistence?,
            commitText: (String) -> Unit,
            onRecordingCompleted: () -> Unit,
            onRecordingError: (String) -> Unit,
        ) {
            var rawTextForPersistence: String? = null

            try {
                val correlationId = ReliabilityEventLogger.newCorrelationId(request.correlationPrefix)
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    outcome = ReliabilityOutcome.STARTED,
                    correlationId = correlationId,
                    reasonCode = "${request.correlationPrefix}_transcription_started",
                )

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
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.TRANSCRIPTION,
                            outcome = ReliabilityOutcome.FAILURE,
                            correlationId = correlationId,
                            reasonCode = "${request.correlationPrefix}_recognizer_not_ready",
                        )
                        withContext(NonCancellable + Dispatchers.Main) {
                            persistence?.persistAudioSource(
                                audioSource = request.audioSource,
                                rawText = null,
                                processedText = null,
                                errorMessage = message,
                            )
                            _phase.value = InlineTranscriptionPhase.Error(message)
                        }
                        onRecordingError(message)
                        return
                    }
                }

                _phase.value = InlineTranscriptionPhase.Transcribing

                val mappedOutcome = transcribeAudioSource(request.audioSource)
                val rawText =
                    when (mappedOutcome) {
                        is InlineTranscriptionResolution.Success -> mappedOutcome.text

                        InlineTranscriptionResolution.NoSpeech -> {
                            ReliabilityEventLogger.log(
                                stage = ReliabilityStage.TRANSCRIPTION,
                                outcome = ReliabilityOutcome.SKIPPED,
                                correlationId = correlationId,
                                reasonCode = "${request.correlationPrefix}_no_speech",
                            )
                            withContext(Dispatchers.Main) {
                                persistence?.discardSamples()
                                onRecordingCompleted()
                                _phase.value = InlineTranscriptionPhase.Idle
                            }
                            return
                        }

                        is InlineTranscriptionResolution.Failure -> {
                            ReliabilityEventLogger.log(
                                stage = ReliabilityStage.TRANSCRIPTION,
                                outcome = ReliabilityOutcome.FAILURE,
                                correlationId = correlationId,
                                reasonCode = "${request.correlationPrefix}_transcription_failed",
                                message = mappedOutcome.message,
                            )
                            withContext(NonCancellable + Dispatchers.Main) {
                                persistence?.persistAudioSource(
                                    audioSource = request.audioSource,
                                    rawText = rawTextForPersistence,
                                    processedText = null,
                                    errorMessage = mappedOutcome.message,
                                )
                                _phase.value = InlineTranscriptionPhase.Error(mappedOutcome.message)
                            }
                            onRecordingError(mappedOutcome.message)
                            return
                        }
                    }

                Log.d(tag, "Transcribed: $rawText")
                rawTextForPersistence = rawText
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    outcome = ReliabilityOutcome.SUCCESS,
                    correlationId = correlationId,
                    reasonCode = "${request.correlationPrefix}_transcription_completed",
                )

                if (request.llmEnabled) {
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.ENHANCEMENT,
                        outcome = ReliabilityOutcome.STARTED,
                        correlationId = correlationId,
                        reasonCode = "${request.correlationPrefix}_enhancement_started",
                    )
                    _phase.value = InlineTranscriptionPhase.Polishing

                    val result =
                        withTimeoutOrNull(10_000L) {
                            textEnhancement.process(rawText, request.processingModeId)
                        }

                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            result.fold(
                                onSuccess = { polishedText ->
                                    ReliabilityEventLogger.log(
                                        stage = ReliabilityStage.ENHANCEMENT,
                                        outcome = ReliabilityOutcome.SUCCESS,
                                        correlationId = correlationId,
                                        reasonCode = "${request.correlationPrefix}_enhancement_completed",
                                    )
                                    commitText("$polishedText ")
                                    persistence?.persistAudioSource(request.audioSource, rawText, polishedText, null)
                                    onRecordingCompleted()
                                    _phase.value = InlineTranscriptionPhase.Idle
                                },
                                onFailure = { error ->
                                    ReliabilityEventLogger.log(
                                        stage = ReliabilityStage.ENHANCEMENT,
                                        outcome = ReliabilityOutcome.FAILURE,
                                        correlationId = correlationId,
                                        reasonCode = "${request.correlationPrefix}_enhancement_failed",
                                        message = error.message,
                                    )
                                    commitText("$rawText ")
                                    persistence?.persistAudioSource(request.audioSource, rawText, null, null)
                                    onRecordingCompleted()
                                    _phase.value =
                                        InlineTranscriptionPhase.LlmError("LLM failed: ${error.message}")
                                },
                            )
                        } else {
                            ReliabilityEventLogger.log(
                                stage = ReliabilityStage.ENHANCEMENT,
                                outcome = ReliabilityOutcome.FAILURE,
                                correlationId = correlationId,
                                reasonCode = "${request.correlationPrefix}_enhancement_timeout",
                            )
                            commitText("$rawText ")
                            persistence?.persistAudioSource(request.audioSource, rawText, null, null)
                            onRecordingCompleted()
                            _phase.value = InlineTranscriptionPhase.Idle
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        commitText("$rawText ")
                        persistence?.persistAudioSource(request.audioSource, rawText, null, null)
                        onRecordingCompleted()
                        _phase.value = InlineTranscriptionPhase.Idle
                    }
                }
            } catch (e: CancellationException) {
                Log.w(tag, "Transcription cancelled", e)
                withContext(NonCancellable + Dispatchers.Main) {
                    persistence?.persistAudioSource(
                        audioSource = request.audioSource,
                        rawText = rawTextForPersistence,
                        processedText = null,
                        errorMessage = "Dictation cancelled",
                    )
                }
                throw e
            } catch (e: Exception) {
                val errorMessage = "Transcription failed: ${e.message}"
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = ReliabilityEventLogger.newCorrelationId(request.correlationPrefix),
                    reasonCode = "${request.correlationPrefix}_exception",
                    message = e.message,
                )
                withContext(NonCancellable + Dispatchers.Main) {
                    persistence?.persistAudioSource(
                        audioSource = request.audioSource,
                        rawText = rawTextForPersistence,
                        processedText = null,
                        errorMessage = errorMessage,
                    )
                    _phase.value = InlineTranscriptionPhase.Error(errorMessage)
                }
                onRecordingError(errorMessage)
            }
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
                    mapInlineTranscriptionOutcome(
                        transcriberProvider.transcribe(audioSource.samples, audioSource.sampleRate),
                    )

                is InlineAudioSource.PcmFloatFile -> {
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

                    if (transcript.isBlank()) {
                        InlineTranscriptionResolution.NoSpeech
                    } else {
                        InlineTranscriptionResolution.Success(transcript)
                    }
                }
            }
    }

private class InlineTranscriptionFailureException(
    message: String,
) : Exception(message)
