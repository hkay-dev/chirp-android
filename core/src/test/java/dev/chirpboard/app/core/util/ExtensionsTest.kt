package dev.chirpboard.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun testIsDefaultDateTitle() {
        assertTrue("Jan 29, 10:30 AM".isDefaultDateTitle())
        assertTrue("Feb 5, 2:15 PM".isDefaultDateTitle())
        assertFalse("My Meeting".isDefaultDateTitle())
        assertTrue("Jan 29, 10:30 AM".isDefaultDateTitle())
    }

    @Test
    fun testTruncate() {
        assertEquals("Hello", "Hello".truncate(10))
        assertEquals("Hello", "Hello".truncate(6))
        assertEquals("...", "Long".truncate(4))
    }

    @Test
    fun testTitleCase() {
        assertEquals("Hello World", "hello world".titleCase())
        assertEquals("A B C", "a b c".titleCase())
        assertEquals("Already Title", "Already Title".titleCase())
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
