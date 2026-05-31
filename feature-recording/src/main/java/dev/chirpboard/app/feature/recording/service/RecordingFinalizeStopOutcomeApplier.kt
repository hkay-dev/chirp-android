package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
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
        when (result) {
            is StopPersistenceResult.SavedAndQueued -> {
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_STOP,
                    outcome = ReliabilityOutcome.SUCCESS,
                    correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    recordingId = result.recordingId,
                    reasonCode = "background_finalize_saved_and_enqueued",
                )
            }

            is StopPersistenceResult.SavedPendingRecovery -> {
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.QUEUE_ENQUEUE,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    recordingId = result.recordingId,
                    reasonCode = "background_finalize_queue_handoff_failed",
                    message = result.message,
                )
            }

            is StopPersistenceResult.PersistenceFailed -> {
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.PERSISTENCE_SAVE,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "background_finalize_persistence_failed",
                    message = result.message,
                )
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
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_STOP,
                    outcome = ReliabilityOutcome.SKIPPED,
                    correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "background_finalize_missing_audio_file",
                )
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
