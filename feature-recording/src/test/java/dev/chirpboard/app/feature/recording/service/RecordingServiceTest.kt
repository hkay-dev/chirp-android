package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingServiceTest {

    @Test
    fun `companion object methods are available`() {
        org.junit.Assert.assertNotNull(RecordingService.Companion)
    }

    @Test
    fun `recording start sequence promotes foreground before async recorder setup`() {
        assertEquals(
            listOf(
                RecordingStartStep.PromoteForeground,
                RecordingStartStep.PrepareRecorderAsync,
            ),
            RecordingStartSequence.stepsAfterLockAcquired(),
        )
    }
}
