package dev.chirpboard.app.core.audio.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureContinuityTrackerTest {
    @Test
    fun `continuous hardware and delivered frames report no gap`() {
        val tracker = VoiceRecorder.CaptureContinuityTracker(sampleRate = 16_000, toleranceFrames = 32)

        assertNull(tracker.observe(framePosition = 1_000, nanoTime = 1_000_000_000, capturedFrames = 1_000))
        assertNull(tracker.observe(framePosition = 2_000, nanoTime = 1_062_500_000, capturedFrames = 2_000))

        assertEquals(Triple(0L, 0, 0), tracker.snapshot())
    }

    @Test
    fun `hardware frames missing from delivery produce a structured gap`() {
        val tracker = VoiceRecorder.CaptureContinuityTracker(sampleRate = 16_000, toleranceFrames = 32)
        tracker.observe(framePosition = 1_000, nanoTime = 1_000_000_000, capturedFrames = 1_000)

        val gap = tracker.observe(framePosition = 3_000, nanoTime = 1_125_000_000, capturedFrames = 2_000)

        assertEquals(968L, gap?.missingFrames)
        assertEquals(Triple(968L, 1, 0), tracker.snapshot())
    }

    @Test
    fun `restart resets the timestamp baseline and remains visible in the report`() {
        val tracker = VoiceRecorder.CaptureContinuityTracker(sampleRate = 16_000, toleranceFrames = 32)
        tracker.observe(framePosition = 4_000, nanoTime = 1_000_000_000, capturedFrames = 4_000)
        tracker.markRecorderRestart()

        assertNull(tracker.observe(framePosition = 100, nanoTime = 1_100_000_000, capturedFrames = 4_100))
        assertTrue(tracker.snapshot().third == 1)
    }
}
