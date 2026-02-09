package dev.chirpboard.app.feature.recording.ui

import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.transcription.RecoveryOwnershipState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRecoveryActionMatrixTest {

    @Test
    fun `pending transcription shows recover action and stays enabled when ownership missing`() {
        val actions = computeDetailRecoveryActions(
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            ownership = RecoveryOwnershipState.MISSING_OR_TERMINAL
        )

        assertTrue(actions.showPendingRecovery)
        assertFalse(actions.showEnhancementRecovery)
        assertTrue(actions.actionsEnabled)
    }

    @Test
    fun `pending transcription disables actions while active work is running`() {
        val actions = computeDetailRecoveryActions(
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            ownership = RecoveryOwnershipState.ACTIVE
        )

        assertTrue(actions.showPendingRecovery)
        assertFalse(actions.actionsEnabled)
    }

    @Test
    fun `enhancing shows both recovery actions and disables on ownership timeout`() {
        val actions = computeDetailRecoveryActions(
            status = RecordingStatus.ENHANCING,
            ownership = RecoveryOwnershipState.INSPECTION_TIMEOUT
        )

        assertTrue(actions.showEnhancementRecovery)
        assertTrue(actions.showRetranscribeFromEnhancing)
        assertFalse(actions.actionsEnabled)
    }

    @Test
    fun `failed retry remains available regardless of ownership state`() {
        val actions = computeDetailRecoveryActions(
            status = RecordingStatus.FAILED,
            ownership = RecoveryOwnershipState.ACTIVE
        )

        assertTrue(actions.showFailedRetry)
        assertTrue(actions.actionsEnabled)
    }

    @Test
    fun `processing filter includes expected states`() {
        assertTrue(isProcessingOrStuckStatus(RecordingStatus.PENDING_TRANSCRIPTION))
        assertTrue(isProcessingOrStuckStatus(RecordingStatus.ENHANCING))
        assertTrue(isProcessingOrStuckStatus(RecordingStatus.TRANSCRIBING))
        assertFalse(isProcessingOrStuckStatus(RecordingStatus.COMPLETED))
    }

    @Test
    fun `list recovery affordance shown only for stuck pending or enhancing`() {
        assertTrue(shouldShowStuckRecoveryAction(RecordingStatus.PENDING_TRANSCRIPTION))
        assertTrue(shouldShowStuckRecoveryAction(RecordingStatus.ENHANCING))
        assertFalse(shouldShowStuckRecoveryAction(RecordingStatus.TRANSCRIBING))
        assertFalse(shouldShowStuckRecoveryAction(RecordingStatus.FAILED))
    }
}
