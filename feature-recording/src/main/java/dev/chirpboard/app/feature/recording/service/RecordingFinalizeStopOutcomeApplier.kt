package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import java.util.UUID

internal object RecordingFinalizeStopOutcomeApplier {
    suspend fun apply(
        result: StopPersistenceResult,
        snapshot: StopSnapshot?,
        sessionId: UUID?,
        sessionJournal: RecordingSessionJournal,
        recordingRepository: RecordingRepository,
    ) {
        val correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record")
        when (result) {
            is StopPersistenceResult.SavedAndQueued -> {
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.RECORDING_STOP,
                        correlationId = correlationId,
                        recordingId = result.recordingId,
                    ).success("background_finalize_saved_and_enqueued")
            }

            is StopPersistenceResult.SavedPendingRecovery -> {
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.QUEUE_ENQUEUE,
                        correlationId = correlationId,
                        recordingId = result.recordingId,
                    ).failure("background_finalize_queue_handoff_failed", message = result.message)
            }

            is StopPersistenceResult.PersistenceFailed -> {
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.PERSISTENCE_SAVE,
                        correlationId = correlationId,
                    ).failure("background_finalize_persistence_failed", message = result.message)
                if (!RecordingFinalizeRecoveryPolicy.hasRecoverableArtifacts(sessionJournal, sessionId, snapshot)) {
                    RecordingFinalizeRecoveryPolicy.cleanupUnrecoverable(
                        sessionJournal = sessionJournal,
                        recordingRepository = recordingRepository,
                        sessionId = sessionId,
                        snapshot = snapshot,
                    )
                }
            }

            StopPersistenceResult.NoAudioFile -> {
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.RECORDING_STOP,
                        correlationId = correlationId,
                    ).skipped("background_finalize_missing_audio_file")
                if (!RecordingFinalizeRecoveryPolicy.hasRecoverableArtifacts(sessionJournal, sessionId, snapshot)) {
                    RecordingFinalizeRecoveryPolicy.cleanupUnrecoverable(
                        sessionJournal = sessionJournal,
                        recordingRepository = recordingRepository,
                        sessionId = sessionId,
                        snapshot = snapshot,
                    )
                }
            }
        }
    }
}
