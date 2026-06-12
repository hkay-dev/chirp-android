package dev.chirpboard.app.core.transcription

import java.util.UUID

enum class ManualRecoveryResult {
    ENQUEUED,
    BLOCKED_ACTIVE_WORK,
    BLOCKED_OWNERSHIP_TIMEOUT,
    NOT_RECOVERABLE_STATE,
}

enum class RecoveryOwnershipState {
    ACTIVE,
    MISSING_OR_TERMINAL,
    INSPECTION_TIMEOUT,
}

enum class ProcessingRecoveryQueueState {
    PENDING_TRANSCRIPTION,
    TRANSCRIBING,
    PENDING_ENHANCEMENT,
    ENHANCING,
    FAILED,
    OTHER,
}

data class ProcessingRecoveryActions(
    val showPendingRecovery: Boolean,
    val showEnhancementRecovery: Boolean,
    val showRetranscribeFromEnhancing: Boolean,
    val showFailedRetry: Boolean,
    val actionsEnabled: Boolean,
)

data class RecoveryDiagnostics(
    val latestReason: String?,
    val lastAttemptEpochMs: Long?,
    val ownership: RecoveryOwnershipState,
)

interface TranscriptionRecovery {
    suspend fun enqueue(
        recordingId: UUID,
        correlationId: String? = null,
    ): String

    suspend fun markPendingForQueueRecovery(
        recordingId: UUID,
        reason: String,
        cause: Throwable?,
    )

    /**
     * Retry a FAILED recording. Returns the actual outcome so callers never report
     * success when the recording is no longer FAILED or the claim was refused; a
     * COMPLETED row is reported as [ManualRecoveryResult.NOT_RECOVERABLE_STATE]
     * (use [retranscribe] to deliberately re-run a finished recording).
     */
    suspend fun retry(recordingId: UUID): ManualRecoveryResult

    /**
     * Explicit user-requested re-transcription. Unlike [enqueue], this may also claim a
     * COMPLETED recording, resetting it back through the transcription pipeline. Returns
     * the actual outcome so callers never report success for a rejected claim.
     */
    suspend fun retranscribe(recordingId: UUID): ManualRecoveryResult

    suspend fun recoverPendingTranscription(recordingId: UUID): ManualRecoveryResult

    suspend fun recoverPendingEnhancement(recordingId: UUID): ManualRecoveryResult

    suspend fun recoverEnhancing(recordingId: UUID): ManualRecoveryResult

    suspend fun retranscribeFromEnhancing(recordingId: UUID): ManualRecoveryResult

    suspend fun recoverStuckRecordings(): Int

    suspend fun recoverRecordingsWaitingForModel()

    /**
     * Cancels queued or running processing for a recording: cancels the scheduled
     * transcription/enhancement work and resolves the row to a neutral state
     * (AWAITING_MANUAL_TRANSCRIPTION before a transcript exists, COMPLETED once one
     * does) instead of FAILED-with-error. Also safe to call before deleting a
     * recording so orphaned workers never spin up for a deleted row.
     */
    suspend fun cancelProcessing(recordingId: UUID)

    suspend fun getRecoveryDiagnostics(recordingId: UUID): RecoveryDiagnostics
}

// I18N-08: refusal copy lives in core-contracts string resources; callers supply their own
// success message (typically a feature-module resource).
fun ManualRecoveryResult.toUserMessage(
    context: android.content.Context,
    success: String,
): String =
    when (this) {
        ManualRecoveryResult.ENQUEUED -> success
        ManualRecoveryResult.BLOCKED_ACTIVE_WORK ->
            context.getString(dev.chirpboard.app.core.contracts.R.string.recovery_blocked_active_work)
        ManualRecoveryResult.BLOCKED_OWNERSHIP_TIMEOUT ->
            context.getString(dev.chirpboard.app.core.contracts.R.string.recovery_blocked_ownership_timeout)
        ManualRecoveryResult.NOT_RECOVERABLE_STATE ->
            context.getString(dev.chirpboard.app.core.contracts.R.string.recovery_not_recoverable_state)
    }

fun deriveProcessingRecoveryActions(
    queueState: ProcessingRecoveryQueueState,
    ownership: RecoveryOwnershipState,
): ProcessingRecoveryActions {
    val ownershipAllowsManualRecovery = ownership == RecoveryOwnershipState.MISSING_OR_TERMINAL

    return when (queueState) {
        ProcessingRecoveryQueueState.PENDING_TRANSCRIPTION ->
            ProcessingRecoveryActions(
                showPendingRecovery = true,
                showEnhancementRecovery = false,
                showRetranscribeFromEnhancing = false,
                showFailedRetry = false,
                actionsEnabled = ownershipAllowsManualRecovery,
            )

        ProcessingRecoveryQueueState.PENDING_ENHANCEMENT ->
            ProcessingRecoveryActions(
                showPendingRecovery = false,
                showEnhancementRecovery = true,
                showRetranscribeFromEnhancing = false,
                showFailedRetry = false,
                actionsEnabled = ownershipAllowsManualRecovery,
            )

        ProcessingRecoveryQueueState.ENHANCING ->
            ProcessingRecoveryActions(
                showPendingRecovery = false,
                showEnhancementRecovery = true,
                showRetranscribeFromEnhancing = true,
                showFailedRetry = false,
                actionsEnabled = ownershipAllowsManualRecovery,
            )

        ProcessingRecoveryQueueState.FAILED ->
            ProcessingRecoveryActions(
                showPendingRecovery = false,
                showEnhancementRecovery = false,
                showRetranscribeFromEnhancing = false,
                showFailedRetry = true,
                actionsEnabled = true,
            )

        ProcessingRecoveryQueueState.TRANSCRIBING,
        ProcessingRecoveryQueueState.OTHER,
        ->
            ProcessingRecoveryActions(
                showPendingRecovery = false,
                showEnhancementRecovery = false,
                showRetranscribeFromEnhancing = false,
                showFailedRetry = false,
                actionsEnabled = ownershipAllowsManualRecovery,
            )
    }
}
