package dev.chirpboard.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `test formatTimeMs`() {
        assertEquals("00:00", formatTimeMs(0))
        assertEquals("00:05", formatTimeMs(5000))
        assertEquals("01:05", formatTimeMs(65000))
    }
}
