package dev.chirpboard.app.feature.transcription

import android.util.Log
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
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
    private val transcriptionRoutingStore: TranscriptionRoutingStore,
    private val setConstraintWarning: (String?) -> Unit,
    private val setActiveCount: (Int) -> Unit
) {
    suspend fun reconcileQueueHealth(trigger: ReconciliationTrigger) {
        Log.i(TAG, "Running queue reconciliation. trigger=$trigger")

        recoverStaleTranscribing(trigger)
        recoverStaleEnhancing(trigger)
        // Load the pending set once per pass and reuse it for both ownership
        // reconciliation and the constraint-warning check below; loading it twice
        // was four redundant status queries per pass for no benefit.
        val pending = loadPendingRecordings()
        reconcilePendingQueueOwnership(pending)
        updateActiveCount()

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

    private suspend fun reconcilePendingQueueOwnership(pending: List<Recording>) {
        // PIPE-03: re-attach oldest-first so a large backlog drains in capture order
        // (mergePendingRecordings sorts newest-first for UI display). WorkManager may run
        // several workers concurrently, but the single recognizer mutex serializes the
        // actual decodes, so enqueue order determines user-visible completion order.
        pending.sortedBy { it.createdAt }.forEach { recording ->
            val ownership = inspectQueueOwnership(recording)

            when {
                shouldRequeuePending(ownership) -> {
                    val correlationId = ReliabilityEventLogger.newCorrelationId("queue-reconcile")
                    val queueLog =
                        ReliabilityEventLogger.scoped(
                            stage = ReliabilityStage.QUEUE_ENQUEUE,
                            correlationId = correlationId,
                            recordingId = recording.id,
                        )
                    try {
                        val scheduledWorkId =
                            enqueueWorkForRecording(
                                recording = recording,
                                correlationId = correlationId,
                            )
                        if (scheduledWorkId == null) {
                            // Claim was rejected: the row moved on (e.g. a worker began it)
                            // between our read and the claim. Nothing was requeued, so do
                            // not log RECOVERED and do not touch the recovery marker.
                            queueLog.skipped("reconcile_claim_rejected")
                        } else {
                            queueLog.recovered("reconciled_pending")
                            if (recording.hasRecoverablePendingError()) {
                                clearPendingError(recording)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Failed to schedule pending recording ${recording.id}", e)
                        queueLog.failure("reconcile_enqueue_failed", e)
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

    /**
     * Clears a recoverable error marker without changing status. The update is pinned to the
     * pending status the row was loaded with, so a row a worker has already promoted to
     * TRANSCRIBING/ENHANCING since our read can never be knocked back to pending here.
     */
    private suspend fun clearPendingError(recording: Recording) {
        recordingRepository.transitionRecordingStatus(
            id = recording.id,
            destinationStatus = recording.status,
            allowedSourceStatuses = listOf(recording.status),
            errorMessage = null,
        )
    }

    /**
     * Claims execution ownership and schedules work for a pending recording.
     *
     * @return the scheduled work id, or null when the claim was rejected (the row is no
     *   longer in a claimable state) and nothing was enqueued.
     */
    private suspend fun enqueueWorkForRecording(
        recording: Recording,
        correlationId: String,
    ): String? =
        withSerializedQueueScheduling {
            val executionToken = UUID.randomUUID().toString()
            when (recording.status) {
                RecordingStatus.PENDING_ENHANCEMENT -> {
                    val claimed =
                        recordingRepository.claimEnhancementExecution(
                            recordingId = recording.id,
                            executionToken = executionToken,
                            status = recording.status,
                            errorMessage = recording.errorMessage,
                        )
                    if (!claimed) {
                        return@withSerializedQueueScheduling null
                    }
                    workScheduler.enqueueEnhancement(
                        recordingId = recording.id,
                        executionToken = executionToken,
                        correlationId = correlationId,
                    )
                }

                else -> {
                    val routedRecording =
                        resolveTranscriptionEngine(recording)
                            ?: return@withSerializedQueueScheduling null
                    val claimed =
                        recordingRepository.claimTranscriptionExecution(
                            recordingId = recording.id,
                            executionToken = executionToken,
                            status = RecordingStatus.PENDING_TRANSCRIPTION,
                            errorMessage = recording.errorMessage,
                        )
                    if (!claimed) {
                        return@withSerializedQueueScheduling null
                    }
                    workScheduler.enqueueTranscription(
                        recordingId = recording.id,
                        executionToken = executionToken,
                        correlationId = correlationId,
                        requiresNetwork = routedRecording.requiresNetworkForTranscription(),
                    )
                }
            }
        }

    private suspend fun resolveTranscriptionEngine(recording: Recording): Recording? =
        if (recording.transcriptionEngineId == null) {
            val selectedEngine = transcriptionRoutingStore.getSelectedEngine()
            recordingRepository.stampTranscriptionEngineIfUnset(recording.id, selectedEngine.id)
        } else {
            recording
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
