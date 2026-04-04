package dev.chirpboard.app.data.db

import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date
import java.util.UUID

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `UUID conversion works`() {
        val uuid = UUID.randomUUID()
        val str = converters.fromUUID(uuid)
        assertEquals(uuid.toString(), str)
        
        val parsed = converters.toUUID(str)
        assertEquals(uuid, parsed)
        
        assertNull(converters.fromUUID(null))
        assertNull(converters.toUUID(null))
    }

    @Test
    fun `Date conversion works`() {
        val date = Date(1680000000000L)
        val time = converters.fromDate(date)
        assertEquals(1680000000000L, time)
        
        val parsed = converters.toDate(time)
        assertEquals(date, parsed)
        
        assertNull(converters.fromDate(null))
        assertNull(converters.toDate(null))
    }

    @Test
    fun `RecordingSource conversion works`() {
        val source = RecordingSource.APP
        val str = converters.fromRecordingSource(source)
        assertEquals("APP", str)
        
        val parsed = converters.toRecordingSource(str)
        assertEquals(source, parsed)
        
        assertNull(converters.fromRecordingSource(null))
        assertNull(converters.toRecordingSource(null))
    }

    @Test
    fun `RecordingStatus conversion works`() {
        val status = RecordingStatus.TRANSCRIBING
        val str = converters.fromRecordingStatus(status)
        assertEquals("TRANSCRIBING", str)
        
        val parsed = converters.toRecordingStatus(str)
        assertEquals(status, parsed)
        
        assertNull(converters.fromRecordingStatus(null))
        assertNull(converters.toRecordingStatus(null))
    }
}
