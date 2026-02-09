package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the queue of recordings pending transcription.
 * 
 * Coordinates between the RecordingRepository for status tracking
 * and WorkManager for background processing.
 */
@Singleton
class TranscriptionQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingRepository: RecordingRepository,
    private val constraintChecker: WorkConstraintChecker
) {
    
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val reconciliationMutex = Mutex()
    private var reconciliationJob: Job? = null
    @Volatile
    private var reconciliationStarted = false
    
    companion object {
        private const val TAG = "TranscriptionQueueMgr"
        private const val MANUAL_RECOVERY_PREFIX = "manual_recovery:"
        private const val RECOVERABLE_QUEUE_HANDOFF_PREFIX = "recoverable_queue_handoff:"
        private const val RECOVERABLE_STALE_TRANSCRIBING_PREFIX = "recoverable_stale_transcribing:"
        private const val RECOVERABLE_STALE_ENHANCING_PREFIX = "recoverable_stale_enhancing:"
        private const val DEFAULT_RECONCILIATION_INTERVAL_MS = 60_000L
        private const val WORK_INFO_TIMEOUT_MS = 5_000L
        private const val WORK_INFO_POLL_INTERVAL_MS = 50L
        private const val TRANSCRIBING_STALE_THRESHOLD_MS = 15 * 60_000L
        private const val ENHANCING_STALE_THRESHOLD_MS = 10 * 60_000L
    }
    
    private val _activeCount = MutableStateFlow(0)
    
    private val _constraintWarning = MutableStateFlow<String?>(null)
    
    /**
     * Warning message when device constraints may delay transcription.
     * Null when all constraints are satisfied.
     * UI can observe this to show snackbar/banner feedback to users.
     */
    val constraintWarning: StateFlow<String?> = _constraintWarning.asStateFlow()
    
    /**
     * Flow of recordings pending transcription.
     * Emits updates whenever the pending queue changes.
     */
    val pendingRecordings: Flow<List<Recording>> =
        recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION)
    
    /**
     * Number of recordings currently being processed (TRANSCRIBING status).
     */
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    /**
     * Start periodic queue reconciliation while the app process is alive.
     * Safe to call multiple times; only the first call starts the loop.
     */
    fun startContinuousReconciliation(
        scope: CoroutineScope,
        intervalMs: Long = DEFAULT_RECONCILIATION_INTERVAL_MS
    ) {
        synchronized(this) {
            if (reconciliationStarted) return
            reconciliationStarted = true
        }

        reconciliationJob = scope.launch {
            while (isActive) {
                try {
                    reconcileQueueHealth(ReconciliationTrigger.PERIODIC)
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic queue reconciliation failed", e)
                }
                delay(intervalMs)
            }
        }
    }
    
    /**
     * Enqueue a recording for transcription.
     * Sets status to PENDING_TRANSCRIPTION and schedules WorkManager job.
     * 
     * Checks device constraints and emits a warning via [constraintWarning] if
     * battery is low or storage is insufficient. The work is still enqueued
     * (WorkManager will wait for constraints), but the user gets feedback.
     * 
     * @param recordingId The UUID of the recording to transcribe
     */
    suspend fun enqueue(recordingId: UUID, correlationId: String? = null) {
        val corrId = correlationId ?: ReliabilityEventLogger.newCorrelationId("queue")

        ReliabilityEventLogger.log(
            stage = ReliabilityStage.QUEUE_ENQUEUE,
            outcome = ReliabilityOutcome.STARTED,
            correlationId = corrId,
            recordingId = recordingId,
            reasonCode = "enqueue_requested"
        )

        // Check constraints and warn user (but still enqueue - WorkManager will wait)
        val status = constraintChecker.checkConstraints()
        _constraintWarning.value = constraintChecker.getConstraintMessage(status)
        
        // Update status to pending and clear stale error metadata
        recordingRepository.updateStatusWithError(
            id = recordingId,
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            errorMessage = null
        )
        
        try {
            // Schedule the work
            TranscriptionWorkRequest.enqueue(context, recordingId, corrId)
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.QUEUE_ENQUEUE,
                outcome = ReliabilityOutcome.SUCCESS,
                correlationId = corrId,
                recordingId = recordingId,
                reasonCode = "enqueue_scheduled"
            )
        } catch (e: Exception) {
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.QUEUE_ENQUEUE,
                outcome = ReliabilityOutcome.FAILURE,
                correlationId = corrId,
                recordingId = recordingId,
                reasonCode = "enqueue_exception",
                message = e.message
            )
            throw e
        }
    }

    /**
     * Mark a recording as recoverable pending when save succeeded but enqueue failed.
     * Startup recovery can use this marker to prioritize queue reattachment.
     */
    suspend fun markPendingForQueueRecovery(
        recordingId: UUID,
        reason: String,
        cause: Throwable? = null
    ) {
        val causeMessage = cause?.message?.takeIf { it.isNotBlank() }
        val errorMessage = if (causeMessage != null) {
            "$RECOVERABLE_QUEUE_HANDOFF_PREFIX$reason Cause: $causeMessage"
        } else {
            "$RECOVERABLE_QUEUE_HANDOFF_PREFIX$reason"
        }

        recordingRepository.updateStatusWithError(
            id = recordingId,
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            errorMessage = errorMessage
        )

        ReliabilityEventLogger.log(
            stage = ReliabilityStage.QUEUE_ENQUEUE,
            outcome = ReliabilityOutcome.RECOVERED,
            correlationId = ReliabilityEventLogger.newCorrelationId("queue-recovery"),
            recordingId = recordingId,
            reasonCode = "pending_for_recovery",
            message = reason
        )
    }
    
    /**
     * Retry a failed transcription.
     * Resets status from FAILED to PENDING_TRANSCRIPTION and re-enqueues.
     * 
     * Checks device constraints and emits a warning via [constraintWarning] if
     * battery is low or storage is insufficient.
     * 
     * @param recordingId The UUID of the recording to retry
     */
    suspend fun retry(recordingId: UUID) {
        val recording = recordingRepository.getRecording(recordingId)
        
        if (recording?.status == RecordingStatus.FAILED) {
            // Check constraints and warn user
            val status = constraintChecker.checkConstraints()
            _constraintWarning.value = constraintChecker.getConstraintMessage(status)
            
            // Clear error and reset status
            recordingRepository.updateStatusWithError(
                id = recordingId,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                errorMessage = null
            )
            
            // Re-enqueue for processing
            TranscriptionWorkRequest.enqueue(
                context,
                recordingId,
                ReliabilityEventLogger.newCorrelationId("queue-retry")
            )
        }
    }

    suspend fun recoverPendingTranscription(recordingId: UUID): ManualRecoveryResult {
        val recording = recordingRepository.getRecording(recordingId)
            ?: return ManualRecoveryResult.NOT_RECOVERABLE_STATE

        if (recording.status != RecordingStatus.PENDING_TRANSCRIPTION) {
            return ManualRecoveryResult.NOT_RECOVERABLE_STATE
        }

        return enqueueManualRecovery(
            recordingId = recordingId,
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            reason = "Re-established pending transcription ownership"
        )
    }

    suspend fun recoverEnhancing(recordingId: UUID): ManualRecoveryResult {
        val recording = recordingRepository.getRecording(recordingId)
            ?: return ManualRecoveryResult.NOT_RECOVERABLE_STATE

        if (recording.status != RecordingStatus.ENHANCING) {
            return ManualRecoveryResult.NOT_RECOVERABLE_STATE
        }

        return enqueueManualRecovery(
            recordingId = recordingId,
            status = RecordingStatus.PENDING_ENHANCEMENT,
            reason = "Queued enhancement-only recovery"
        )
    }

    suspend fun retranscribeFromEnhancing(recordingId: UUID): ManualRecoveryResult {
        val recording = recordingRepository.getRecording(recordingId)
            ?: return ManualRecoveryResult.NOT_RECOVERABLE_STATE

        if (recording.status != RecordingStatus.ENHANCING) {
            return ManualRecoveryResult.NOT_RECOVERABLE_STATE
        }

        return enqueueManualRecovery(
            recordingId = recordingId,
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            reason = "Queued full retranscription from enhancing"
        )
    }

    suspend fun recoverStuckRecordings(): Int {
        val pending = recordingRepository
            .getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION)
            .first()
        val enhancing = recordingRepository
            .getRecordingsByStatus(RecordingStatus.ENHANCING)
            .first()

        return (pending.map { it.id } + enhancing.map { it.id }).count { id ->
            when {
                pending.any { it.id == id } -> recoverPendingTranscription(id) == ManualRecoveryResult.ENQUEUED
                else -> recoverEnhancing(id) == ManualRecoveryResult.ENQUEUED
            }
        }
    }

    suspend fun getRecoveryDiagnostics(recordingId: UUID): RecoveryDiagnostics {
        val recording = recordingRepository.getRecording(recordingId)
        val ownership = inspectQueueOwnership(recordingId).toRecoveryOwnershipState()
        val parsed = parseRecoveryMetadata(recording?.errorMessage)

        return RecoveryDiagnostics(
            latestReason = parsed.reason,
            lastAttemptEpochMs = parsed.lastAttemptEpochMs,
            ownership = ownership
        )
    }
    
    /**
     * Cancel pending transcription.
     * Cancels WorkManager work and updates status.
     * 
     * @param recordingId The UUID of the recording to cancel
     */
    suspend fun cancel(recordingId: UUID) {
        val recording = recordingRepository.getRecording(recordingId)
        
        if (recording != null) {
            // Cancel any pending work for this recording
            workManager.cancelUniqueWork(TranscriptionWorkRequest.workName(recordingId))
            
            // If it was actively transcribing, mark as pending so it can be retried
            // If it was just pending, keep it pending
            when (recording.status) {
                RecordingStatus.TRANSCRIBING -> {
                    recordingRepository.updateStatus(recordingId, RecordingStatus.PENDING_TRANSCRIPTION)
                }
                RecordingStatus.PENDING_TRANSCRIPTION -> {
                    // Already pending, nothing to change
                }
                else -> {
                    // For other statuses, don't change
                }
            }
        }
    }
    
    /**
     * Clear the constraint warning.
     * Call this after the UI has displayed the warning to the user.
     */
    fun clearConstraintWarning() {
        _constraintWarning.value = null
    }
    
    /**
     * Process all pending recordings on app startup.
     * Call this from Application.onCreate or a startup initializer.
     * 
     * First recovers any recordings stuck in TRANSCRIBING status (from app kill),
     * then queries all PENDING_TRANSCRIPTION recordings and ensures each
     * has a WorkManager job scheduled.
     * 
     * Also checks device constraints and emits a warning if there are pending
     * recordings but constraints are not met.
     */
    suspend fun processPendingOnStartup() {
        reconcileQueueHealth(ReconciliationTrigger.STARTUP)
    }

    private suspend fun reconcileQueueHealth(trigger: ReconciliationTrigger) {
        reconciliationMutex.withLock {
            Log.i(TAG, "Running queue reconciliation. trigger=$trigger")

            recoverStaleTranscribing(trigger)
            recoverStaleEnhancing(trigger)
            reconcilePendingQueueOwnership()

            updateActiveCount()

            val pending = pendingRecordings.first()
            if (pending.isNotEmpty()) {
                val status = constraintChecker.checkConstraints()
                _constraintWarning.value = constraintChecker.getConstraintMessage(status)
            }
        }
    }

    private suspend fun recoverStaleTranscribing(trigger: ReconciliationTrigger) {
        val now = System.currentTimeMillis()
        val transcribing = recordingRepository
            .getRecordingsByStatus(RecordingStatus.TRANSCRIBING)
            .first()

        transcribing.forEach { recording ->
            val ownership = inspectQueueOwnership(recording.id)
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
            .first()

        enhancing.forEach { recording ->
            val ownership = inspectQueueOwnership(recording.id)
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
                    status = RecordingStatus.FAILED,
                    errorMessage = reason
                )
            }
        }
    }

    private suspend fun reconcilePendingQueueOwnership() {
        val pending = pendingRecordings.first()

        pending.forEach { recording ->
            val ownership = inspectQueueOwnership(recording.id)

            when {
                shouldRequeuePending(ownership) -> {
                    try {
                        TranscriptionWorkRequest.enqueue(
                            context,
                            recording.id,
                            ReliabilityEventLogger.newCorrelationId("queue-reconcile")
                        )
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.QUEUE_ENQUEUE,
                            outcome = ReliabilityOutcome.RECOVERED,
                            correlationId = ReliabilityEventLogger.newCorrelationId("queue-reconcile"),
                            recordingId = recording.id,
                            reasonCode = "reconciled_pending"
                        )
                        if (recording.hasRecoverablePendingError()) {
                            clearPendingError(recording.id)
                        }
                    } catch (e: Exception) {
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
                        clearPendingError(recording.id)
                    }
                }

                else -> {
                    Log.w(TAG, "Timed out inspecting work ownership for pending ${recording.id}")
                }
            }
        }
    }

    private suspend fun inspectQueueOwnership(recordingId: UUID): QueueOwnership {
        return try {
            val workInfos = loadWorkInfosWithTimeout(TranscriptionWorkRequest.workName(recordingId))
                ?: return QueueOwnership.INSPECTION_TIMEOUT

            val hasActiveWork = workInfos.any { info ->
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED
            }

            if (hasActiveWork) QueueOwnership.ACTIVE else QueueOwnership.MISSING_OR_TERMINAL
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect queue ownership for $recordingId", e)
            QueueOwnership.INSPECTION_TIMEOUT
        }
    }

    private suspend fun enqueueManualRecovery(
        recordingId: UUID,
        status: RecordingStatus,
        reason: String
    ): ManualRecoveryResult {
        val ownership = inspectQueueOwnership(recordingId)
        val blockResult = blockedManualRecoveryResult(ownership)
        if (blockResult != null) {
            return blockResult
        }

        val statusCheck = constraintChecker.checkConstraints()
        _constraintWarning.value = constraintChecker.getConstraintMessage(statusCheck)

        recordingRepository.updateStatusWithError(
            id = recordingId,
            status = status,
            errorMessage = buildManualRecoveryMessage(reason)
        )

        TranscriptionWorkRequest.enqueue(
            context,
            recordingId,
            ReliabilityEventLogger.newCorrelationId("queue-manual-recovery")
        )

        return ManualRecoveryResult.ENQUEUED
    }

    private fun buildManualRecoveryMessage(reason: String): String {
        return "$MANUAL_RECOVERY_PREFIX$reason|attemptAt=${System.currentTimeMillis()}"
    }

    private suspend fun loadWorkInfosWithTimeout(workName: String): List<WorkInfo>? {
        return withContext(Dispatchers.IO) {
            val future = workManager.getWorkInfosForUniqueWork(workName)

            withTimeoutOrNull(WORK_INFO_TIMEOUT_MS) {
                while (!future.isDone && !future.isCancelled) {
                    delay(WORK_INFO_POLL_INTERVAL_MS)
                }

                if (future.isCancelled) {
                    emptyList()
                } else {
                    future.get()
                }
            }
        }
    }

    private suspend fun clearPendingError(recordingId: UUID) {
        recordingRepository.updateStatusWithError(
            id = recordingId,
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            errorMessage = null
        )
    }
    
    /**
     * Update the active count by querying recordings with TRANSCRIBING status.
     */
    private suspend fun updateActiveCount() {
        val transcribing = recordingRepository
            .getRecordingsByStatus(RecordingStatus.TRANSCRIBING)
            .first()
        _activeCount.value = transcribing.size
    }

    private fun Recording.hasRecoverablePendingError(): Boolean {
        return errorMessage?.startsWith(RECOVERABLE_QUEUE_HANDOFF_PREFIX) == true ||
            errorMessage?.startsWith(RECOVERABLE_STALE_TRANSCRIBING_PREFIX) == true
    }
}

private data class ParsedRecoveryMetadata(
    val reason: String?,
    val lastAttemptEpochMs: Long?
)

private fun parseRecoveryMetadata(errorMessage: String?): ParsedRecoveryMetadata {
    if (errorMessage.isNullOrBlank()) {
        return ParsedRecoveryMetadata(reason = null, lastAttemptEpochMs = null)
    }

    val normalizedReason = errorMessage
        .removePrefix("recoverable_queue_handoff:")
        .removePrefix("recoverable_stale_transcribing:")
        .removePrefix("recoverable_stale_enhancing:")

    if (!normalizedReason.startsWith("manual_recovery:")) {
        return ParsedRecoveryMetadata(reason = normalizedReason, lastAttemptEpochMs = null)
    }

    val payload = normalizedReason.removePrefix("manual_recovery:")
    val attemptToken = "|attemptAt="
    val attemptIndex = payload.indexOf(attemptToken)
    if (attemptIndex < 0) {
        return ParsedRecoveryMetadata(reason = payload, lastAttemptEpochMs = null)
    }

    val reason = payload.substring(0, attemptIndex)
    val timestampRaw = payload.substring(attemptIndex + attemptToken.length)
    val timestamp = timestampRaw.toLongOrNull()

    return ParsedRecoveryMetadata(reason = reason, lastAttemptEpochMs = timestamp)
}

internal fun blockedManualRecoveryResult(ownership: QueueOwnership): ManualRecoveryResult? {
    return when (ownership) {
        QueueOwnership.ACTIVE -> ManualRecoveryResult.BLOCKED_ACTIVE_WORK
        QueueOwnership.INSPECTION_TIMEOUT -> ManualRecoveryResult.BLOCKED_OWNERSHIP_TIMEOUT
        QueueOwnership.MISSING_OR_TERMINAL -> null
    }
}

private fun QueueOwnership.toRecoveryOwnershipState(): RecoveryOwnershipState {
    return when (this) {
        QueueOwnership.ACTIVE -> RecoveryOwnershipState.ACTIVE
        QueueOwnership.MISSING_OR_TERMINAL -> RecoveryOwnershipState.MISSING_OR_TERMINAL
        QueueOwnership.INSPECTION_TIMEOUT -> RecoveryOwnershipState.INSPECTION_TIMEOUT
    }
}

enum class ManualRecoveryResult {
    ENQUEUED,
    BLOCKED_ACTIVE_WORK,
    BLOCKED_OWNERSHIP_TIMEOUT,
    NOT_RECOVERABLE_STATE
}

enum class RecoveryOwnershipState {
    ACTIVE,
    MISSING_OR_TERMINAL,
    INSPECTION_TIMEOUT
}

data class RecoveryDiagnostics(
    val latestReason: String?,
    val lastAttemptEpochMs: Long?,
    val ownership: RecoveryOwnershipState
)

internal enum class ReconciliationTrigger {
    STARTUP,
    PERIODIC
}

internal enum class QueueOwnership {
    ACTIVE,
    MISSING_OR_TERMINAL,
    INSPECTION_TIMEOUT
}

internal fun shouldRecoverStaleTranscribing(
    trigger: ReconciliationTrigger,
    createdAtEpochMs: Long,
    ownership: QueueOwnership,
    nowEpochMs: Long,
    staleThresholdMs: Long
): Boolean {
    if (ownership == QueueOwnership.ACTIVE || ownership == QueueOwnership.INSPECTION_TIMEOUT) {
        return false
    }

    return trigger == ReconciliationTrigger.STARTUP ||
        (nowEpochMs - createdAtEpochMs) >= staleThresholdMs
}

internal fun shouldRecoverStaleEnhancing(
    trigger: ReconciliationTrigger,
    createdAtEpochMs: Long,
    ownership: QueueOwnership,
    nowEpochMs: Long,
    staleThresholdMs: Long
): Boolean {
    if (ownership == QueueOwnership.ACTIVE || ownership == QueueOwnership.INSPECTION_TIMEOUT) {
        return false
    }

    return trigger == ReconciliationTrigger.STARTUP ||
        (nowEpochMs - createdAtEpochMs) >= staleThresholdMs
}

internal fun shouldRequeuePending(ownership: QueueOwnership): Boolean {
    return ownership == QueueOwnership.MISSING_OR_TERMINAL
}
