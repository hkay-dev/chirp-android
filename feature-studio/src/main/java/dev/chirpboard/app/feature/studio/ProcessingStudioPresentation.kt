package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.data.model.RecordingStatus

internal data class StudioFailurePresentation(
    val showRecoverySection: Boolean,
    val showErrorBanner: Boolean,
    val showRetryOnErrorBanner: Boolean,
)

/**
 * FAILED recordings use a single error banner with retry; recovery blocks stay hidden to avoid duplicates.
 */
internal fun studioFailurePresentation(
    status: RecordingStatus?,
    errorMessage: String?,
    recoveryActions: TranscriptionRecoveryActionsUi,
): StudioFailurePresentation {
    val isFailed = status == RecordingStatus.FAILED
    if (isFailed) {
        return StudioFailurePresentation(
            showRecoverySection = false,
            showErrorBanner = true,
            showRetryOnErrorBanner = recoveryActions.showFailedRetry,
        )
    }
    val showRecovery =
        recoveryActions.showPendingRecovery ||
            recoveryActions.showEnhancementRecovery ||
            recoveryActions.showFailedRetry
    return StudioFailurePresentation(
        showRecoverySection = showRecovery,
        showErrorBanner = !errorMessage.isNullOrBlank(),
        showRetryOnErrorBanner = false,
    )
}
