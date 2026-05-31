package dev.chirpboard.app

import android.util.Log
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceRecognitionCaptureGateTest {
    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `acquire starts keyboard-origin shared recording and release returns idle`() {
        val manager = RecordingStateManager()
        val gate = VoiceRecognitionCaptureGate(manager)

        assertEquals(VoiceRecognitionCaptureGateResult.Acquired, gate.tryAcquire())
        assertTrue(gate.isHeld())
        assertTrue(manager.state.value is RecordingState.Starting)
        assertEquals(RecordingOrigin.KEYBOARD, manager.state.value.activeOrigin)

        gate.onRecorderStarted("voice_recognition_temp_recording")
        assertTrue(manager.state.value is RecordingState.Recording)
        assertEquals("voice_recognition_temp_recording", (manager.state.value as RecordingState.Recording).audioFilePath)

        gate.releaseCompleted()

        assertFalse(gate.isHeld())
        assertEquals(RecordingState.Idle, manager.state.value)
    }

    @Test
    fun `acquire returns busy source when another recording owns the lock`() {
        val manager = RecordingStateManager()
        val gate = VoiceRecognitionCaptureGate(manager)

        assertEquals(RecordingStartResult.Success, manager.tryStartRecording(RecordingOrigin.APP))

        val result = gate.tryAcquire()

        assertEquals(VoiceRecognitionCaptureGateResult.Busy("app"), result)
        assertFalse(gate.isHeld())
        assertTrue(manager.state.value is RecordingState.Starting)
        assertEquals(RecordingOrigin.APP, manager.state.value.activeOrigin)
    }

    @Test
    fun `release error clears ownership and reports shared recording error`() {
        val manager = RecordingStateManager()
        val gate = VoiceRecognitionCaptureGate(manager)

        assertEquals(VoiceRecognitionCaptureGateResult.Acquired, gate.tryAcquire())

        gate.releaseError("Failed to start voice recognition")

        assertFalse(gate.isHeld())
        val state = manager.state.value
        assertTrue(state is RecordingState.Error)
        assertEquals(RecordingOrigin.KEYBOARD, (state as RecordingState.Error).origin)
        assertEquals("Failed to start voice recognition", state.message)
        assertEquals(RecordingStartResult.Success, manager.tryStartRecording(RecordingOrigin.APP))
    }
}
