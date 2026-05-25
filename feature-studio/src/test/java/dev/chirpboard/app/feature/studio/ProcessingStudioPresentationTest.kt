package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.core.transcription.RecoveryOwnershipState
import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStudioPresentationTest {
    @Test
    fun `failed status shows single error banner with retry and hides recovery section`() {
        val presentation =
            studioFailurePresentation(
                status = RecordingStatus.FAILED,
                errorMessage = "Transcription failed",
                recoveryActions =
                    TranscriptionRecoveryActionsUi(
                        showPendingRecovery = false,
                        showEnhancementRecovery = false,
                        showRetranscribeFromEnhancing = false,
                        showFailedRetry = true,
                        actionsEnabled = true,
                    ),
            )

        assertTrue(presentation.showErrorBanner)
        assertTrue(presentation.showRetryOnErrorBanner)
        assertFalse(presentation.showRecoverySection)
    }

    @Test
    fun `non-failed recoverable error keeps recovery section separate from banner`() {
        val presentation =
            studioFailurePresentation(
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                errorMessage = "recoverable_queue_handoff:stuck",
                recoveryActions =
                    computeTranscriptionRecoveryActions(
                        status = RecordingStatus.PENDING_TRANSCRIPTION,
                        ownership = RecoveryOwnershipState.MISSING_OR_TERMINAL,
                    ),
            )

        assertTrue(presentation.showRecoverySection)
        assertTrue(presentation.showErrorBanner)
        assertFalse(presentation.showRetryOnErrorBanner)
    }
}
