package dev.chirpboard.app.core.ui.components.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimerTest {
    @Test
    fun snapToSecond_truncatesSubSecondMillis() {
        assertEquals(0L, snapToSecond(0L))
        assertEquals(0L, snapToSecond(999L))
        assertEquals(1000L, snapToSecond(1000L))
        assertEquals(1000L, snapToSecond(1099L))
        assertEquals(1000L, snapToSecond(1900L))
        assertEquals(2000L, snapToSecond(2000L))
    }

    @Test
    fun snapToSecond_isStableAcrossSubSecondTicks() {
        // The 100 ms tick produces these raw values within one second; all must map to 7000.
        val rawTicksWithinSecond = listOf(7000L, 7100L, 7200L, 7300L, 7400L, 7500L, 7600L, 7700L, 7800L, 7900L)
        rawTicksWithinSecond.forEach { raw ->
            assertEquals(7000L, snapToSecond(raw))
        }
    }

    @Test
    fun snapToSecond_handlesMinuteBoundary() {
        assertEquals(60_000L, snapToSecond(60_000L))
        assertEquals(60_000L, snapToSecond(60_950L))
        assertEquals(61_000L, snapToSecond(61_000L))
    }
}
