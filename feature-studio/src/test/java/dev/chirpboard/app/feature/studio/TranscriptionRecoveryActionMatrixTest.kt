package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.core.transcription.RecoveryOwnershipState
import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionRecoveryActionMatrixTest {
    @Test
    fun `pending transcription shows recover action and stays enabled when ownership missing`() {
        val actions =
            computeTranscriptionRecoveryActions(
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                ownership = RecoveryOwnershipState.MISSING_OR_TERMINAL,
            )

        assertTrue(actions.showPendingRecovery)
        assertFalse(actions.showEnhancementRecovery)
        assertTrue(actions.actionsEnabled)
    }

    @Test
    fun `pending transcription disables actions while active work is running`() {
        val actions =
            computeTranscriptionRecoveryActions(
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                ownership = RecoveryOwnershipState.ACTIVE,
            )

        assertTrue(actions.showPendingRecovery)
        assertFalse(actions.actionsEnabled)
    }

    @Test
    fun `pending enhancement shows enhancement recovery without full retranscription`() {
        val actions =
            computeTranscriptionRecoveryActions(
                status = RecordingStatus.PENDING_ENHANCEMENT,
                ownership = RecoveryOwnershipState.MISSING_OR_TERMINAL,
            )

        assertFalse(actions.showPendingRecovery)
        assertTrue(actions.showEnhancementRecovery)
        assertFalse(actions.showRetranscribeFromEnhancing)
        assertTrue(actions.actionsEnabled)
    }

    @Test
    fun `enhancing shows both recovery actions and disables on ownership timeout`() {
        val actions =
            computeTranscriptionRecoveryActions(
                status = RecordingStatus.ENHANCING,
                ownership = RecoveryOwnershipState.INSPECTION_TIMEOUT,
            )

        assertTrue(actions.showEnhancementRecovery)
        assertTrue(actions.showRetranscribeFromEnhancing)
        assertFalse(actions.actionsEnabled)
    }

    @Test
    fun `failed retry remains available regardless of ownership state`() {
        val actions =
            computeTranscriptionRecoveryActions(
                status = RecordingStatus.FAILED,
                ownership = RecoveryOwnershipState.ACTIVE,
            )

        assertTrue(actions.showFailedRetry)
        assertTrue(actions.actionsEnabled)
    }
}
