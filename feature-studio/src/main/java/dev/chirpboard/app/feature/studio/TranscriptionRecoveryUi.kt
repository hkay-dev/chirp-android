package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.RecoveryOwnershipState
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
): TranscriptionRecoveryActionsUi {
    val isRecoverableStatus =
        status == RecordingStatus.PENDING_TRANSCRIPTION ||
            status == RecordingStatus.ENHANCING
    val disabledByOwnership =
        ownership == RecoveryOwnershipState.ACTIVE ||
            ownership == RecoveryOwnershipState.INSPECTION_TIMEOUT

    return TranscriptionRecoveryActionsUi(
        showPendingRecovery = status == RecordingStatus.PENDING_TRANSCRIPTION,
        showEnhancementRecovery = status == RecordingStatus.ENHANCING,
        showRetranscribeFromEnhancing = status == RecordingStatus.ENHANCING,
        showFailedRetry = status == RecordingStatus.FAILED,
        actionsEnabled = !isRecoverableStatus || !disabledByOwnership,
    )
}

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
