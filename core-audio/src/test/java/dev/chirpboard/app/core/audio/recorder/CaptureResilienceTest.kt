package dev.chirpboard.app.core.audio.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CaptureResilienceTest {
    @Test
    fun `capture buffers are stable within a collector and isolated across collectors`() {
        val firstPool = VoiceRecorder.CaptureBufferPool(sampleCapacity = 1_024)
        val secondPool = VoiceRecorder.CaptureBufferPool(sampleCapacity = 1_024)

        assertSame(firstPool.readSamples, firstPool.readSamples)
        assertSame(firstPool.pcmBytes, firstPool.pcmBytes)
        assertEquals(1_024, firstPool.readSamples.size)
        assertEquals(4_096, firstPool.pcmBytes.size)
        org.junit.Assert.assertNotSame(firstPool.readSamples, secondPool.readSamples)
        org.junit.Assert.assertNotSame(firstPool.pcmBytes, secondPool.pcmBytes)
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

}
