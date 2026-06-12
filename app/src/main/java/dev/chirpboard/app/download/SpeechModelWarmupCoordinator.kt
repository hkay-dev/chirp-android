package dev.chirpboard.app.download

import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechModelWarmupCoordinator
    @Inject
    constructor(
        private val recordingRepository: RecordingRepository,
        private val readinessGate: SpeechModelReadinessGate,
    ) {
        suspend fun warmupOnAppStartupIfCandidate() {
            when (detectStartupCandidate()) {
                SpeechModelWarmupCandidate.QueuedTranscription ->
                    readinessGate.warmupIfNeeded(VerificationTrigger.QUEUED_TRANSCRIPTION)

                SpeechModelWarmupCandidate.Recovery ->
                    readinessGate.warmupIfNeeded(VerificationTrigger.RECOVERY)

                null -> Unit
            }
        }

        internal suspend fun detectStartupCandidate(): SpeechModelWarmupCandidate? {
            val pending = recordingRepository.getPendingRecordings()
            if (pending.any { it.status == RecordingStatus.PENDING_TRANSCRIPTION }) {
                return SpeechModelWarmupCandidate.QueuedTranscription
            }

            val failed = recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED).first().value
            return if (failed.any(Recording::isWaitingForSpeechModelRecovery)) {
                SpeechModelWarmupCandidate.Recovery
            } else {
                null
            }
        }
    }

enum class SpeechModelWarmupCandidate {
    QueuedTranscription,
    Recovery,
}

// I18N-06 WARNING: this classification is keyed off display-string prefixes persisted into
// Recording.errorMessage by TranscriptionWorker/TranscriptionWorkerSupport ("Speech model
// unavailable: …" etc.). Do NOT reword those producer strings without updating this matcher
// in the same change; the durable fix is a typed waiting-for-model reason code on the
// recording row (data module + worker, owned by the pipeline group) — until then this
// coupling is load-bearing for model-download recovery.
internal fun Recording.isWaitingForSpeechModelRecovery(): Boolean =
    errorMessage?.let { message ->
        message.startsWith("Model not downloaded") ||
            message.startsWith("Failed to initialize") ||
            message.startsWith("Speech model unavailable") ||
            message.startsWith("Recognizer not ready")
    } ?: false
