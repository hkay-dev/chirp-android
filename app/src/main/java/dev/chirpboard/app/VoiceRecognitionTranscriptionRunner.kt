package dev.chirpboard.app

import android.util.Log
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionPort
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.feature.transcription.QuickInputResultNotificationPublisher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns a stopped voice-dialog capture until transcription, persistence, and notification finish.
 * The process scope keeps that work alive when the recognition activity leaves the foreground.
 */
@Singleton
class VoiceRecognitionTranscriptionRunner
    @Inject
    constructor(
        private val transcription: InlineTranscriptionPort,
        private val capturePersistence: InlineCapturePersistence,
        private val notificationPublisher: QuickInputResultNotificationPublisher,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun start(request: Request): Session {
            val result = CompletableDeferred<Outcome>()
            val job =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val outcome = transcribe(request)
                        result.complete(outcome)
                        if (!request.secure && outcome.committedText.isNotBlank()) {
                            notificationPublisher.show(
                                rawText = outcome.rawText ?: outcome.committedText,
                                processedText = outcome.processedText,
                            )
                        }
                    } catch (error: CancellationException) {
                        result.cancel(error)
                        throw error
                    } catch (error: Exception) {
                        result.completeExceptionally(error)
                        Log.e(TAG, "Quick-input transcription owner failed", error)
                    }
                }
            return Session(result) { userInitiated ->
                if (userInitiated) transcription.markUserCancelled()
                job.cancel()
            }
        }

        private suspend fun transcribe(request: Request): Outcome {
            var committedText = ""
            var completedRawText: String? = null
            var completedProcessedText: String? = null
            val persistence =
                if (request.secure) {
                    SecureRecognitionCapturePersistence
                } else {
                    DictationCapturePersistenceGuard(
                        delegate = capturePersistence,
                        completionErrorMessage = request.captureFailureMessage,
                    ) { rawText, processedText ->
                        completedRawText = rawText
                        completedProcessedText = processedText
                    }
                }

            if (!request.secure && request.audioSource is InlineAudioSource.PcmFloatFile) {
                try {
                    capturePersistence.checkpointAudioSource(
                        audioSource = request.audioSource,
                        trustedSampleCount = request.audioSource.sampleCount,
                        partialTranscript = null,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Could not checkpoint stopped quick-input audio", error)
                }
            }

            transcription.transcribe(
                request =
                    InlineTranscriptionRequest(
                        audioSource = request.audioSource,
                        llmEnabled = request.llmEnabled && !request.secure,
                        processingModeId = request.processingModeId,
                        correlationPrefix = "voice",
                    ),
                persistence = persistence,
                commitText = { text -> committedText = text.trim() },
                onRecordingError = { message -> Log.e(TAG, message) },
            )
            if (persistence is DictationCapturePersistenceGuard) {
                persistence.persistDeferredRescueIfNeeded()
            }

            return Outcome(
                committedText = committedText,
                rawText = completedRawText,
                processedText = completedProcessedText,
                terminalPhase = transcription.phase.value,
            )
        }

        data class Request(
            val audioSource: InlineAudioSource,
            val llmEnabled: Boolean,
            val processingModeId: String,
            val secure: Boolean,
            val captureFailureMessage: String? = null,
        )

        data class Outcome(
            val committedText: String,
            val rawText: String?,
            val processedText: String?,
            val terminalPhase: InlineTranscriptionPhase,
        )

        class Session internal constructor(
            val result: Deferred<Outcome>,
            private val cancelSession: (Boolean) -> Unit,
        ) {
            fun cancel(userInitiated: Boolean) = cancelSession(userInitiated)
        }

        private companion object {
            const val TAG = "VoiceTranscription"
        }
    }
