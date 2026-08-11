package dev.chirpboard.app.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class ExtensionsTest {

    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalZone)
    }

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
    fun formatRelative_followsATimeZoneChangeWithinTheSameProcess() {
        // 23:30 UTC is already the next calendar day fourteen hours east. The formatter
        // used to pin the zone at first use, so a device that travelled (or corrected its
        // zone) kept labelling every recording card with the old one until it restarted.
        val instant =
            LocalDate.now(ZoneId.of("UTC")).minusDays(10).atTime(23, 30).atZone(ZoneId.of("UTC")).toInstant()
        val date = Date.from(instant)

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val utcLabel = date.formatRelative()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))

        assertNotEquals(utcLabel, date.formatRelative())
    }
}
