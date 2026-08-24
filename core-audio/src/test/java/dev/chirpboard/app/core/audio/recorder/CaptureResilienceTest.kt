package dev.chirpboard.app.core.audio.recorder

import android.media.AudioRecord
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun `a healthy read clears a latched empty-read issue`() {
        val monitor = VoiceRecorder.CaptureHealthMonitor(zeroReadLimit = 3)
        repeat(3) {
            monitor.onReadStarted(nowMs = it.toLong())
            monitor.onReadCompleted(result = 0)
        }
        assertEquals(VoiceRecorder.CaptureHealthIssue.RepeatedZeroReads, monitor.issueAt(nowMs = 4))

        monitor.onReadStarted(nowMs = 5)
        monitor.onReadCompleted(result = 1_024)

        assertNull(monitor.issueAt(nowMs = 6))
    }

    @Test
    fun `an error read keeps the latched issue until a restart`() {
        val monitor = VoiceRecorder.CaptureHealthMonitor(zeroReadLimit = 2)
        repeat(2) {
            monitor.onReadStarted(nowMs = it.toLong())
            monitor.onReadCompleted(result = 0)
        }

        monitor.onReadStarted(nowMs = 3)
        monitor.onReadCompleted(result = -3)

        assertEquals(VoiceRecorder.CaptureHealthIssue.RepeatedZeroReads, monitor.issueAt(nowMs = 4))
    }

    @Test
    fun `a superseded record is released once, by whichever side gets there first`() =
        runTest {
            val record = mockk<AudioRecord>(relaxed = true)
            val stale = VoiceRecorder.StaleCapture(record)

            assertTrue(stale.release())
            assertTrue(stale.awaitRelease(timeoutMs = 1_000))
            assertFalse(stale.release())

            verify(exactly = 1) { record.release() }
        }

    @Test
    fun `a recovery frees the superseded record itself when the collector never hands it back`() =
        runTest {
            val record = mockk<AudioRecord>(relaxed = true)
            val stale = VoiceRecorder.StaleCapture(record)

            assertFalse(stale.awaitRelease(timeoutMs = 50))
            verify(exactly = 0) { record.release() }

            assertTrue(stale.release())
            verify(exactly = 1) { record.release() }
        }
}
