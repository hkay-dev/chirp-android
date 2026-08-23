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

/** Verifies model files needed by queued recovery work. It never constructs the native recognizer. */
@Singleton
class SpeechModelReadinessCoordinator
    @Inject
    constructor(
        private val recordingRepository: RecordingRepository,
        private val readinessGate: SpeechModelReadinessGate,
        private val transcriptionRoutingStore: TranscriptionRoutingStore,
    ) {
        suspend fun verifyOnAppStartupIfCandidate() {
            when (detectStartupCandidate()) {
                SpeechModelReadinessCandidate.QueuedTranscription ->
                    readinessGate.verifyIfNeeded(VerificationTrigger.QUEUED_TRANSCRIPTION)

                SpeechModelReadinessCandidate.Recovery ->
                    readinessGate.verifyIfNeeded(VerificationTrigger.RECOVERY)

                null -> Unit
            }
        }

        internal suspend fun detectStartupCandidate(): SpeechModelReadinessCandidate? {
            val pending = recordingRepository.getPendingRecordings()
            for (recording in pending) {
                if (recording.status == RecordingStatus.PENDING_TRANSCRIPTION &&
                    resolveEngine(recording) == TranscriptionEngine.LOCAL_PARAKEET
                ) {
                    return SpeechModelReadinessCandidate.QueuedTranscription
                }
            }

            val failedState = recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED).first()
            return when {
                // An errored read emits an empty fallback list; treating that as "no recovery
                // work" would silently skip model verification for real waiting recordings.
                // verifyIfNeeded is idempotent and cheap, so verify on the unknown.
                failedState.errorMessage != null -> SpeechModelReadinessCandidate.Recovery
                failedState.value.any(Recording::isWaitingForSpeechModelRecovery) ->
                    SpeechModelReadinessCandidate.Recovery

                else -> null
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

enum class SpeechModelReadinessCandidate {
    QueuedTranscription,
    Recovery,
}

// I18N-06: classification now goes through the single typed classifier in the data module
// (classifyRecordingProcessingNote). The persisted markers are a frozen machine contract,
// decoupled from user-facing copy — rewording UI strings can no longer change recovery
// behavior.
internal fun Recording.isWaitingForSpeechModelRecovery(): Boolean = isWaitingForSpeechModel(errorMessage)
