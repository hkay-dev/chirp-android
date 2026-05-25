package dev.chirpboard.app.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RecordingStateTest {

    @Test
    fun idleState_isNotActive_hasNullOrigin() {
        val state = RecordingState.Idle
        assertFalse(state.isActive)
        assertNull(state.activeOrigin)
    }

    @Test
    fun startingState_isActive_hasCorrectOrigin() {
        val state = RecordingState.Starting(RecordingOrigin.APP, UUID.randomUUID())
        assertTrue(state.isActive)
        assertEquals(RecordingOrigin.APP, state.activeOrigin)
    }

    @Test
    fun startingState_exposesActiveRecordingIdWhenAssigned() {
        val recordingId = UUID.randomUUID()
        val state = RecordingState.Starting(RecordingOrigin.APP, UUID.randomUUID(), recordingId)
        assertEquals(recordingId, state.activeRecordingId)
    }

    @Test
    fun recordingState_isActive_hasCorrectOrigin() {
        val state = RecordingState.Recording(RecordingOrigin.KEYBOARD, UUID.randomUUID(), 1000L, "path")
        assertTrue(state.isActive)
        assertEquals(RecordingOrigin.KEYBOARD, state.activeOrigin)
    }

    @Test
    fun pausedState_isActive_hasCorrectOrigin() {
        val state = RecordingState.Paused(RecordingOrigin.WIDGET, UUID.randomUUID(), "path", 5000L)
        assertTrue(state.isActive)
        assertEquals(RecordingOrigin.WIDGET, state.activeOrigin)
    }

    @Test
    fun stoppingState_isActive_hasCorrectOrigin() {
        val state = RecordingState.Stopping(RecordingOrigin.APP, UUID.randomUUID())
        assertTrue(state.isActive)
        assertEquals(RecordingOrigin.APP, state.activeOrigin)
    }

    @Test
    fun errorState_isNotActive_hasNullOrigin() {
        val state = RecordingState.Error(RecordingOrigin.KEYBOARD, "Error message")
        assertFalse(state.isActive)
        assertNull(state.activeOrigin)
    }
}
