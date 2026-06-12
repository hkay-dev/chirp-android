package dev.chirpboard.app.core.ui.components.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingGlowBackgroundTest {

    private val floor = 0.04f
    private val peak = 0.35f

    @Test
    fun glowAlpha_atRestIsTheNearTransparentFloor() {
        // At progress 0 the glow sits at its floor so the resting state is calm, not an error wash.
        assertEquals(floor, glowAlpha(0f, floor, peak), 0.0001f)
    }

    @Test
    fun glowAlpha_atFullProgressReachesPeak() {
        assertEquals(peak, glowAlpha(1f, floor, peak), 0.0001f)
    }

    @Test
    fun glowAlpha_isMonotonicAcrossBreath() {
        var previous = glowAlpha(0f, floor, peak)
        listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f).forEach { p ->
            val current = glowAlpha(p, floor, peak)
            assertTrue("alpha must increase toward the peak", current > previous)
            previous = current
        }
    }

    @Test
    fun glowAlpha_clampsOutOfRangeProgress() {
        assertEquals(floor, glowAlpha(-0.5f, floor, peak), 0.0001f)
        assertEquals(peak, glowAlpha(1.5f, floor, peak), 0.0001f)
    }

    @Test
    fun glowAlpha_neverExceedsPeakWithinRange() {
        // The breathing band must never paint above its configured peak (no over-saturated flash).
        listOf(0f, 0.33f, 0.5f, 0.99f, 1f).forEach { p ->
            assertTrue(glowAlpha(p, floor, peak) <= peak + 0.0001f)
        }
    }
}
