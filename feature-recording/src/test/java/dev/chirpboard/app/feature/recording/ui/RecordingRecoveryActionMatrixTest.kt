package dev.chirpboard.app.feature.recording.ui

import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRecoveryActionMatrixTest {
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
