package dev.chirpboard.app.core.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformBufferTest {
    @Test
    fun `snapshotInto copies samples oldest to newest before wraparound`() {
        val buffer = WaveformBuffer(capacity = 4)
        buffer.add(0.1f)
        buffer.add(0.2f)
        buffer.add(0.3f)

        val dest = FloatArray(4)
        assertEquals(3, buffer.snapshotInto(dest))
        assertEquals(0.1f, dest[0])
        assertEquals(0.2f, dest[1])
        assertEquals(0.3f, dest[2])
    }

    @Test
    fun `snapshotInto preserves order after the ring wraps`() {
        val buffer = WaveformBuffer(capacity = 3)
        listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f).forEach(buffer::add)

        val dest = FloatArray(3)
        assertEquals(3, buffer.snapshotInto(dest))
        assertEquals(0.3f, dest[0])
        assertEquals(0.4f, dest[1])
        assertEquals(0.5f, dest[2])
    }

    @Test
    fun `snapshotInto matches per-index get`() {
        val buffer = WaveformBuffer(capacity = 5)
        listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f).forEach(buffer::add)

        val dest = FloatArray(5)
        val copied = buffer.snapshotInto(dest)
        assertEquals(buffer.count, copied)
        repeat(copied) { i -> assertEquals(buffer.get(i), dest[i]) }
    }

    @Test
    fun `snapshotInto truncates to destination size`() {
        val buffer = WaveformBuffer(capacity = 4)
        listOf(0.1f, 0.2f, 0.3f, 0.4f).forEach(buffer::add)

        val dest = FloatArray(2)
        assertEquals(2, buffer.snapshotInto(dest))
    }

    @Test
    fun `snapshotInto returns zero for an empty buffer`() {
        val buffer = WaveformBuffer(capacity = 4)
        assertEquals(0, buffer.snapshotInto(FloatArray(4)))
    }
}
