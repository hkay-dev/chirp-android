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

internal fun Recording.isWaitingForSpeechModelRecovery(): Boolean =
    errorMessage?.let { message ->
        message.startsWith("Model not downloaded") ||
            message.startsWith("Failed to initialize") ||
            message.startsWith("Speech model unavailable") ||
            message.startsWith("Recognizer not ready")
    } ?: false
