package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingServiceEventsTest {
    private val events = RecordingServiceEvents()

    // ERR-13 staleness: a days-old "stopped and saved" event must be detectable as stale at
    // display time so it can be consumed silently instead of greeting the next app open.
    @Test
    fun `auto-stop event is stale once older than the display age cap`() {
        val event = RecordingAutoStopEvent(reason = RecordingAutoStopReason.STORAGE_CRITICAL, atEpochMs = 0L)

        assertTrue(event.isStale(nowEpochMs = RecordingAutoStopEvent.MAX_DISPLAY_AGE_MS + 1))
    }

    @Test
    fun `auto-stop event at exactly the display age cap is still fresh`() {
        // Boundary: "older than", not "at least" — keeps the deliberate re-surface behavior
        // for events whose snackbar was interrupted moments ago.
        val event = RecordingAutoStopEvent(reason = RecordingAutoStopReason.FOCUS_LOST, atEpochMs = 0L)

        assertFalse(event.isStale(nowEpochMs = RecordingAutoStopEvent.MAX_DISPLAY_AGE_MS))
    }

    @Test
    fun `freshly published auto-stop event is not stale`() {
        events.publishAutoStop(RecordingAutoStopReason.CAPTURE_ERROR)

        val event = events.autoStopEvent.value
        assertEquals(RecordingAutoStopReason.CAPTURE_ERROR, event?.reason)
        assertFalse(event!!.isStale())
    }

    @Test
    fun `display age cap is about five minutes`() {
        assertEquals(5 * 60_000L, RecordingAutoStopEvent.MAX_DISPLAY_AGE_MS)
    }

    // Reliability guard: session-state reset clears the transient advisory flags but never
    // the auto-stop event — the reason must survive the session teardown that caused it.
    @Test
    fun `resetSessionState keeps the auto-stop event but clears transient flags`() {
        events.publishAutoStop(RecordingAutoStopReason.INPUT_DEVICE_LOST)
        events.setAutoPauseReason(RecordingAutoPauseReason.FOCUS_LOST_TRANSIENT)
        events.setSilenceDetected(true)
        events.setStorageLow(true)

        events.resetSessionState()

        assertEquals(RecordingAutoStopReason.INPUT_DEVICE_LOST, events.autoStopEvent.value?.reason)
        assertNull(events.autoPauseReason.value)
        assertFalse(events.silenceDetected.value)
        assertFalse(events.storageLow.value)
    }

    @Test
    fun `clearAutoStopEvent acknowledges the event`() {
        events.publishAutoStop(RecordingAutoStopReason.STORAGE_CRITICAL)

        events.clearAutoStopEvent()

        assertNull(events.autoStopEvent.value)
    }
}
