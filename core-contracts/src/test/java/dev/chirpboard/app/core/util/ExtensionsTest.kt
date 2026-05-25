package dev.chirpboard.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ExtensionsTest {

    @Test
    fun testFormatDuration() {
        assertEquals("0:00", 0L.formatAsDuration())
        assertEquals("0:45", 45000L.formatAsDuration())
        assertEquals("1:05", 65000L.formatAsDuration())
        assertEquals("1:00:00", 3600000L.formatAsDuration())
        assertEquals("1:01:05", 3665000L.formatAsDuration())
    }

    @Test
    fun testFormatRelativeToday() {
        val now = Calendar.getInstance().time
        assertEquals("Today", now.formatRelative())
    }
    
    @Test
    fun testFormatRelativeYesterday() {
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        assertEquals("Yesterday", yesterday.formatRelative())
    }
    
    @Test
    fun testFormatForHeaderToday() {
        val now = Calendar.getInstance().time
        assertTrue(now.formatForHeader().startsWith("Today at "))
    }
}
