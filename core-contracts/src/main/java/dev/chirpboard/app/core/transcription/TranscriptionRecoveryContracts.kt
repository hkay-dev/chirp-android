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

    suspend fun retry(recordingId: UUID)

    suspend fun recoverPendingTranscription(recordingId: UUID): ManualRecoveryResult

    suspend fun recoverPendingEnhancement(recordingId: UUID): ManualRecoveryResult

    suspend fun recoverEnhancing(recordingId: UUID): ManualRecoveryResult

    suspend fun retranscribeFromEnhancing(recordingId: UUID): ManualRecoveryResult

    suspend fun recoverStuckRecordings(): Int

    suspend fun recoverRecordingsWaitingForModel()

    suspend fun getRecoveryDiagnostics(recordingId: UUID): RecoveryDiagnostics
}

fun ManualRecoveryResult.toUserMessage(success: String): String =
    when (this) {
        ManualRecoveryResult.ENQUEUED -> success
        ManualRecoveryResult.BLOCKED_ACTIVE_WORK ->
            "Already processing. Recovery disabled while active work runs"
        ManualRecoveryResult.BLOCKED_OWNERSHIP_TIMEOUT ->
            "Could not verify processing ownership. Try again shortly"
        ManualRecoveryResult.NOT_RECOVERABLE_STATE ->
            "Recovery is unavailable for this state"
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
