package dev.chirpboard.app.core.audio.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CaptureResilienceTest {
    @Test
    fun `storage reservation grows only at slab boundaries`() {
        val allocations = mutableListOf<Long>()
        val remaining = mutableListOf<Long>()
        val slab = 4L * 1024L
        val reservation = VoiceRecorder.SlabStorageReservation(slab, allocations::add, remaining::add)

        reservation.ensureCapacity(1)
        reservation.commitThrough(1)
        reservation.ensureCapacity(slab)
        reservation.commitThrough(slab)
        reservation.ensureCapacity(slab + 1)
        reservation.commitThrough(slab + 1)
        reservation.ensureCapacity(slab * 2)

        assertEquals(listOf(slab, slab), allocations)
        assertEquals(listOf(slab - 1, 0L, slab - 1), remaining)
        assertEquals(slab * 2, reservation.reservedBytes)
    }

    @Test
    fun `capture buffers are stable pooled instances`() {
        val pool = VoiceRecorder.CaptureBufferPool(sampleCapacity = 1_024)

        assertSame(pool.readSamples, pool.readSamples)
        assertSame(pool.pcmBytes, pool.pcmBytes)
        assertEquals(1_024, pool.readSamples.size)
        assertEquals(4_096, pool.pcmBytes.size)
    }

    @Test
    fun `watchdog detects a stalled blocking read`() {
        val monitor = VoiceRecorder.CaptureHealthMonitor(stallTimeoutMs = 500)
        monitor.onReadStarted(nowMs = 1_000)

        assertNull(monitor.issueAt(nowMs = 1_499))
        assertEquals(VoiceRecorder.CaptureHealthIssue.StalledRead, monitor.issueAt(nowMs = 1_500))
    }

    @Test
    fun `watchdog detects repeated empty reads and resets after recovery`() {
        val monitor = VoiceRecorder.CaptureHealthMonitor(zeroReadLimit = 3)
        repeat(3) {
            monitor.onReadStarted(nowMs = it.toLong())
            monitor.onReadCompleted(result = 0)
        }

        assertEquals(VoiceRecorder.CaptureHealthIssue.RepeatedZeroReads, monitor.issueAt(nowMs = 4))
        monitor.markRestart()
        assertNull(monitor.issueAt(nowMs = 5))
    }

    @Test
    fun `watchdog distinguishes severe timestamp drift`() {
        val monitor = VoiceRecorder.CaptureHealthMonitor(severeTimestampDriftFrames = 8_000)

        monitor.onTimestampGap(7_999)
        assertNull(monitor.issueAt(nowMs = 1))
        monitor.onTimestampGap(8_000)
        assertEquals(VoiceRecorder.CaptureHealthIssue.TimestampDrift, monitor.issueAt(nowMs = 2))
    }
}
