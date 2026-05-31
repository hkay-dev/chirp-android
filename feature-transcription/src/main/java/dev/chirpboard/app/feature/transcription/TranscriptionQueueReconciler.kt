package dev.chirpboard.app.feature.transcription

import android.util.Log
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import kotlinx.coroutines.flow.first
import java.util.UUID

internal class TranscriptionQueueReconciler(
    private val recordingRepository: RecordingRepository,
    private val constraintChecker: WorkConstraintChecker,
    private val workScheduler: TranscriptionWorkScheduler,
    private val setConstraintWarning: (String?) -> Unit,
    private val setActiveCount: (Int) -> Unit
) {
    suspend fun reconcileQueueHealth(trigger: ReconciliationTrigger) {
        Log.i(TAG, "Running queue reconciliation. trigger=$trigger")

        recoverStaleTranscribing(trigger)
        recoverStaleEnhancing(trigger)
        reconcilePendingQueueOwnership()
        updateActiveCount()

        val pending = loadPendingRecordings()
        if (pending.isNotEmpty()) {
            val status = constraintChecker.checkConstraints()
            setConstraintWarning(constraintChecker.getConstraintMessage(status))
        }
    }

    suspend fun getRecoveryDiagnostics(recordingId: UUID): RecoveryDiagnostics {
        val recording = recordingRepository.getRecording(recordingId)
        val ownership =
            (if (recording != null) {
                inspectQueueOwnership(recording)
            } else {
                inspectQueueOwnership(recordingId)
            }).toRecoveryOwnershipState()
        val parsed = parseRecoveryMetadata(recording?.errorMessage)

        return RecoveryDiagnostics(
            latestReason = parsed.reason,
            lastAttemptEpochMs = parsed.lastAttemptEpochMs,
            ownership = ownership
        )
    }

    private suspend fun loadPendingRecordings(): List<Recording> {
        val pendingTranscription = recordingRepository
            .getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION)
            .firstValueOrLog("PENDING_TRANSCRIPTION")
        val pendingEnhancement = recordingRepository
            .getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT)
            .firstValueOrLog("PENDING_ENHANCEMENT")

        return mergePendingRecordings(pendingTranscription, pendingEnhancement)
    }

    private suspend fun recoverStaleTranscribing(trigger: ReconciliationTrigger) {
        val now = System.currentTimeMillis()
        val transcribing = recordingRepository
            .getRecordingsByStatus(RecordingStatus.TRANSCRIBING)
            .firstValueOrLog("TRANSCRIBING")

        transcribing.forEach { recording ->
            val ownership = inspectQueueOwnership(recording)
            val shouldRecover = shouldRecoverStaleTranscribing(
                trigger = trigger,
                createdAtEpochMs = recording.createdAt.time,
                ownership = ownership,
                nowEpochMs = now,
                staleThresholdMs = TRANSCRIBING_STALE_THRESHOLD_MS
            )

            if (shouldRecover) {
                val reason = "${RECOVERABLE_STALE_TRANSCRIBING_PREFIX}Recovered stale transcribing state"
                Log.w(TAG, "Recovering stale TRANSCRIBING recording ${recording.id}")
                recordingRepository.updateStatusWithError(
                    id = recording.id,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = reason
                )
            }
        }
    }

    private suspend fun recoverStaleEnhancing(trigger: ReconciliationTrigger) {
        val now = System.currentTimeMillis()
        val enhancing = recordingRepository
            .getRecordingsByStatus(RecordingStatus.ENHANCING)
            .firstValueOrLog("ENHANCING")

        enhancing.forEach { recording ->
            val ownership = inspectQueueOwnership(recording)
            val shouldRecover = shouldRecoverStaleEnhancing(
                trigger = trigger,
                createdAtEpochMs = recording.createdAt.time,
                ownership = ownership,
                nowEpochMs = now,
                staleThresholdMs = ENHANCING_STALE_THRESHOLD_MS
            )

            if (shouldRecover) {
                val reason = "${RECOVERABLE_STALE_ENHANCING_PREFIX}Enhancement stalled; you can retry"
                Log.w(TAG, "Recovering stale ENHANCING recording ${recording.id}")
                recordingRepository.updateStatusWithError(
                    id = recording.id,
                    status = RecordingStatus.PENDING_ENHANCEMENT,
                    errorMessage = reason
                )
            }
        }
    }

    private suspend fun reconcilePendingQueueOwnership() {
        loadPendingRecordings().forEach { recording ->
            val ownership = inspectQueueOwnership(recording)

            when {
                shouldRequeuePending(ownership) -> {
                    try {
                        enqueueWorkForRecording(
                            recording = recording,
                            correlationId = ReliabilityEventLogger.newCorrelationId("queue-reconcile"),
                        )
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.QUEUE_ENQUEUE,
                            outcome = ReliabilityOutcome.RECOVERED,
                            correlationId = ReliabilityEventLogger.newCorrelationId("queue-reconcile"),
                            recordingId = recording.id,
                            reasonCode = "reconciled_pending"
                        )
                        if (recording.hasRecoverablePendingError()) {
                            clearPendingError(recording)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Failed to schedule pending recording ${recording.id}", e)
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.QUEUE_ENQUEUE,
                            outcome = ReliabilityOutcome.FAILURE,
                            correlationId = ReliabilityEventLogger.newCorrelationId("queue-reconcile"),
                            recordingId = recording.id,
                            reasonCode = "reconcile_enqueue_failed",
                            message = e.message
                        )
                    }
                }

                ownership == QueueOwnership.ACTIVE -> {
                    if (recording.hasRecoverablePendingError()) {
                        clearPendingError(recording)
                    }
                }

                else -> {
                    Log.w(TAG, "Timed out inspecting work ownership for pending ${recording.id}")
                }
            }
        }
    }

    internal suspend fun inspectQueueOwnership(recordingId: UUID): QueueOwnership {
        return try {
            val workInfos =
                workScheduler.getWorkInfosByRecordingTag(recordingId)
                ?: return QueueOwnership.INSPECTION_TIMEOUT

            ownershipFromWorkInfos(workInfos)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to inspect queue ownership for $recordingId", e)
            QueueOwnership.INSPECTION_TIMEOUT
        }
    }

    private suspend fun inspectQueueOwnership(recording: Recording): QueueOwnership {
        val workName =
            when (recording.status) {
                RecordingStatus.PENDING_ENHANCEMENT,
                RecordingStatus.ENHANCING,
                -> RecordingEnhancementWorkRequest.workName(recording.id)

                else -> TranscriptionWorkRequest.workName(recording.id)
            }

        return try {
            val workInfos =
                workScheduler.getWorkInfosForUniqueWork(workName)
                    ?: return QueueOwnership.INSPECTION_TIMEOUT

            ownershipFromWorkInfos(workInfos)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to inspect queue ownership for ${recording.id}", e)
            QueueOwnership.INSPECTION_TIMEOUT
        }
    }

    private fun ownershipFromWorkInfos(workInfos: List<ScheduledWorkInfo>): QueueOwnership {
        val hasActiveWork =
            workInfos.any { info ->
                info.state == ScheduledWorkState.ENQUEUED ||
                    info.state == ScheduledWorkState.RUNNING ||
                    info.state == ScheduledWorkState.BLOCKED
            }
        return if (hasActiveWork) QueueOwnership.ACTIVE else QueueOwnership.MISSING_OR_TERMINAL
    }

    private suspend fun clearPendingError(recording: Recording) {
        recordingRepository.updateStatusWithError(
            id = recording.id,
            status = recording.status,
            errorMessage = null
        )
    }

    private suspend fun enqueueWorkForRecording(
        recording: Recording,
        correlationId: String,
    ): String {
        val executionToken = UUID.randomUUID().toString()
        return when (recording.status) {
            RecordingStatus.PENDING_ENHANCEMENT -> {
                if (!recordingRepository.claimEnhancementExecution(recording.id, executionToken, recording.status, recording.errorMessage)) {
                    return ""
                }
                workScheduler.enqueueEnhancement(
                    recordingId = recording.id,
                    executionToken = executionToken,
                    correlationId = correlationId,
                )
            }

            else -> {
                recordingRepository.claimTranscriptionExecution(
                    recordingId = recording.id,
                    executionToken = executionToken,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = recording.errorMessage,
                )
                workScheduler.enqueueTranscription(
                    recordingId = recording.id,
                    executionToken = executionToken,
                    correlationId = correlationId,
                )
            }
        }
    }

    private suspend fun updateActiveCount() {
        val transcribing = recordingRepository
            .getRecordingsByStatus(RecordingStatus.TRANSCRIBING)
            .firstValueOrLog("TRANSCRIBING")
        setActiveCount(transcribing.size)
    }

    private fun Recording.hasRecoverablePendingError(): Boolean {
        return errorMessage?.startsWith(RECOVERABLE_QUEUE_HANDOFF_PREFIX) == true ||
            errorMessage?.startsWith(RECOVERABLE_STALE_TRANSCRIBING_PREFIX) == true ||
            errorMessage?.startsWith(RECOVERABLE_STALE_ENHANCING_PREFIX) == true ||
            errorMessage?.startsWith(MANUAL_RECOVERY_PREFIX) == true
    }

    private companion object {
        private const val TAG = "TranscriptionQueueMgr"
        private const val TRANSCRIBING_STALE_THRESHOLD_MS = 15 * 60_000L
        private const val ENHANCING_STALE_THRESHOLD_MS = 10 * 60_000L
    }
}

private suspend fun kotlinx.coroutines.flow.Flow<RepositoryFlowState<List<Recording>>>.firstValueOrLog(
    statusLabel: String,
): List<Recording> {
    val state = first()
    state.errorMessage?.let { message ->
        Log.e("TranscriptionQueueReconciler", "Failed to load $statusLabel recordings: $message")
    }
    return state.value
}
