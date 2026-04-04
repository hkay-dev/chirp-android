package dev.chirpboard.app.feature.recording.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `test formatDuration`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:05", formatDuration(5000))
        assertEquals("01:05", formatDuration(65000))
    }
}
