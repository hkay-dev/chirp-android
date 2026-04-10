package dev.chirpboard.app.feature.keyboard.state

import dev.chirpboard.app.core.recording.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardStateTest {
    @Test
    fun `toKeyboardState maps Idle correctly`() {
        val recordingState = RecordingState.Idle
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Idle, keyboardState)
    }

    @Test
    fun `toKeyboardState maps Starting to Recording`() {
        val recordingState = RecordingState.Starting(dev.chirpboard.app.core.recording.RecordingOrigin.KEYBOARD)
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Recording, keyboardState)
    }

    @Test
    fun `toKeyboardState maps Recording correctly`() {
        val recordingState = RecordingState.Recording(origin = dev.chirpboard.app.core.recording.RecordingOrigin.KEYBOARD)
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Recording, keyboardState)
    }

    @Test
    fun `toKeyboardState maps Paused to Recording`() {
        val recordingState = RecordingState.Paused(origin = dev.chirpboard.app.core.recording.RecordingOrigin.KEYBOARD)
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Recording, keyboardState)
    }

    @Test
    fun `toKeyboardState maps Stopping to Transcribing`() {
        val recordingState = RecordingState.Stopping(dev.chirpboard.app.core.recording.RecordingOrigin.KEYBOARD)
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Transcribing, keyboardState)
    }

    @Test
    fun `toKeyboardState maps Error correctly`() {
        val errorMessage = "Microphone unavailable"
        val recordingState = RecordingState.Error(dev.chirpboard.app.core.recording.RecordingOrigin.KEYBOARD, errorMessage)
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Error(errorMessage), keyboardState)
    }
}
