package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCaptureProgressWatchdogTest {
    @Test
    fun advancingBytes_neverTriggersStall() {
        val watchdog = RecordingCaptureProgressWatchdog(stallTimeoutMs = 10_000L)

        assertFalse(watchdog.observe(capturedBytes = 0L, nowMs = 0L))
        assertFalse(watchdog.observe(capturedBytes = 1_024L, nowMs = 9_000L))
        assertFalse(watchdog.observe(capturedBytes = 2_048L, nowMs = 18_000L))
    }

    @Test
    fun unchangedBytes_triggerOnceAtTimeout() {
        val watchdog = RecordingCaptureProgressWatchdog(stallTimeoutMs = 10_000L)

        assertFalse(watchdog.observe(capturedBytes = 512L, nowMs = 1_000L))
        assertFalse(watchdog.observe(capturedBytes = 512L, nowMs = 10_999L))
        assertTrue(watchdog.observe(capturedBytes = 512L, nowMs = 11_000L))
        assertFalse(watchdog.observe(capturedBytes = 512L, nowMs = 21_000L))
    }

    @Test
    fun counterReset_restartsTimeout() {
        val watchdog = RecordingCaptureProgressWatchdog(stallTimeoutMs = 10_000L)

        assertFalse(watchdog.observe(capturedBytes = 5_000L, nowMs = 0L))
        assertFalse(watchdog.observe(capturedBytes = 0L, nowMs = 9_000L))
        assertFalse(watchdog.observe(capturedBytes = 0L, nowMs = 18_999L))
        assertTrue(watchdog.observe(capturedBytes = 0L, nowMs = 19_000L))
    }
}
