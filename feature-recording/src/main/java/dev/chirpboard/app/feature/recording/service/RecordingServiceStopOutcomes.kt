package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal enum class StopOutcomeApplyResult {
    Applied,
    StaleGeneration,
}

/**
 * Applies service-owned stop persistence outcomes with stop-generation guarding.
 */
internal object RecordingServiceStopOutcomeApplier {
    suspend fun apply(
        result: StopPersistenceResult,
        snapshot: StopSnapshot?,
        sessionId: UUID?,
        generation: Int,
        stopGeneration: AtomicInteger,
        sessionJournal: RecordingSessionJournal,
        recordingRepository: RecordingRepository,
        recordingStateManager: RecordingStateManager,
        refreshRecovery: suspend () -> Unit,
    ): StopOutcomeApplyResult {
        if (generation != stopGeneration.get()) {
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_STOP,
                outcome = ReliabilityOutcome.SKIPPED,
                correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                reasonCode = "stop_generation_stale",
            )
            return StopOutcomeApplyResult.StaleGeneration
        }

        when (result) {
            is StopPersistenceResult.SavedAndQueued -> {
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_STOP,
                    outcome = ReliabilityOutcome.SUCCESS,
                    correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    recordingId = result.recordingId,
                    reasonCode = "saved_and_enqueued",
                )
                recordingStateManager.onRecordingCompleted(recordingId = result.recordingId)
                refreshRecovery()
            }

            is StopPersistenceResult.SavedPendingRecovery -> {
                android.util.Log.w(
                    "RecordingService",
                    "Saved recording ${result.recordingId} but queue handoff failed. " +
                        "Marked for startup recovery.",
                )
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.QUEUE_ENQUEUE,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    recordingId = result.recordingId,
                    reasonCode = "queue_handoff_failed",
                    message = result.message,
                )
                recordingStateManager.onRecordingCompleted(recordingId = result.recordingId)
                refreshRecovery()
            }

            is StopPersistenceResult.PersistenceFailed -> {
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.PERSISTENCE_SAVE,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "persistence_failed",
                    message = result.message,
                )
                sessionId?.let { sessionJournal.markAbandoned(it) }
                snapshot?.recordingId?.let { inProgressId ->
                    withContext(Dispatchers.IO) {
                        recordingRepository.deleteInProgressRecording(inProgressId)
                    }
                }
                recordingStateManager.onRecordingError(result.message, result.cause)
            }

            StopPersistenceResult.NoAudioFile -> {
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_STOP,
                    outcome = ReliabilityOutcome.SKIPPED,
                    correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "missing_audio_file",
                )
                sessionId?.let { sessionJournal.markAbandoned(it) }
                snapshot?.recordingId?.let { inProgressId ->
                    withContext(Dispatchers.IO) {
                        recordingRepository.deleteInProgressRecording(inProgressId)
                    }
                }
                recordingStateManager.onRecordingCompleted()
            }
        }
        return StopOutcomeApplyResult.Applied
    }
}
