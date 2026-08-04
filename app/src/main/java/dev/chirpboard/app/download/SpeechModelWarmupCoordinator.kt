package dev.chirpboard.app.download

import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.isWaitingForSpeechModel
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
        private val transcriptionRoutingStore: TranscriptionRoutingStore,
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
            for (recording in pending) {
                if (recording.status == RecordingStatus.PENDING_TRANSCRIPTION &&
                    resolveEngine(recording) == TranscriptionEngine.LOCAL_PARAKEET
                ) {
                    return SpeechModelWarmupCandidate.QueuedTranscription
                }
            }

            val failed = recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED).first().value
            return if (failed.any(Recording::isWaitingForSpeechModelRecovery)) {
                SpeechModelWarmupCandidate.Recovery
            } else {
                null
            }
        }

        private suspend fun resolveEngine(recording: Recording): TranscriptionEngine? {
            val routedRecording =
                if (recording.transcriptionEngineId == null) {
                    val defaultEngine = transcriptionRoutingStore.getSelectedEngine()
                    recordingRepository.stampTranscriptionEngineIfUnset(recording.id, defaultEngine.id)
                        ?: return null
                } else {
                    recording
                }
            return TranscriptionEngine.fromId(routedRecording.transcriptionEngineId)
        }
    }

enum class SpeechModelWarmupCandidate {
    QueuedTranscription,
    Recovery,
}

// I18N-06: classification now goes through the single typed classifier in the data module
// (classifyRecordingProcessingNote). The persisted markers are a frozen machine contract,
// decoupled from user-facing copy — rewording UI strings can no longer change recovery
// behavior.
internal fun Recording.isWaitingForSpeechModelRecovery(): Boolean = isWaitingForSpeechModel(errorMessage)
