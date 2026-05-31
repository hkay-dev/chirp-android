package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.core.transcription.ProcessingRecoveryActions
import dev.chirpboard.app.core.transcription.ProcessingRecoveryQueueState
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.RecoveryOwnershipState
import dev.chirpboard.app.core.transcription.deriveProcessingRecoveryActions
import dev.chirpboard.app.data.model.RecordingStatus

data class RecoveryDiagnosticsUi(
    val latestReason: String? = null,
    val lastAttemptEpochMs: Long? = null,
    val ownership: RecoveryOwnershipState = RecoveryOwnershipState.MISSING_OR_TERMINAL,
)

data class TranscriptionRecoveryActionsUi(
    val showPendingRecovery: Boolean,
    val showEnhancementRecovery: Boolean,
    val showRetranscribeFromEnhancing: Boolean,
    val showFailedRetry: Boolean,
    val actionsEnabled: Boolean,
)

fun computeTranscriptionRecoveryActions(
    status: RecordingStatus?,
    ownership: RecoveryOwnershipState,
): TranscriptionRecoveryActionsUi =
    deriveProcessingRecoveryActions(
        queueState = status.toProcessingRecoveryQueueState(),
        ownership = ownership,
    ).toUiModel()

fun RecoveryDiagnostics.toUiModel(): RecoveryDiagnosticsUi =
    RecoveryDiagnosticsUi(
        latestReason = latestReason,
        lastAttemptEpochMs = lastAttemptEpochMs,
        ownership = ownership,
    )

object TranscriptionRecoveryTestTags {
    const val PendingRecoverButton = "pending_recover_button"
    const val EnhancingRecoverButton = "enhancing_recover_button"
    const val EnhancingRetranscribeButton = "enhancing_retranscribe_button"
}

private fun RecordingStatus?.toProcessingRecoveryQueueState(): ProcessingRecoveryQueueState =
    when (this) {
        RecordingStatus.PENDING_TRANSCRIPTION -> ProcessingRecoveryQueueState.PENDING_TRANSCRIPTION
        RecordingStatus.TRANSCRIBING -> ProcessingRecoveryQueueState.TRANSCRIBING
        RecordingStatus.PENDING_ENHANCEMENT -> ProcessingRecoveryQueueState.PENDING_ENHANCEMENT
        RecordingStatus.ENHANCING -> ProcessingRecoveryQueueState.ENHANCING
        RecordingStatus.FAILED -> ProcessingRecoveryQueueState.FAILED
        else -> ProcessingRecoveryQueueState.OTHER
    }

private fun ProcessingRecoveryActions.toUiModel(): TranscriptionRecoveryActionsUi =
    TranscriptionRecoveryActionsUi(
        showPendingRecovery = showPendingRecovery,
        showEnhancementRecovery = showEnhancementRecovery,
        showRetranscribeFromEnhancing = showRetranscribeFromEnhancing,
        showFailedRetry = showFailedRetry,
        actionsEnabled = actionsEnabled,
    )
