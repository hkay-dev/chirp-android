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
        val recordingState = RecordingState.Starting("test")
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Recording, keyboardState)
    }

    @Test
    fun `toKeyboardState maps Recording correctly`() {
        val recordingState = RecordingState.Recording(System.currentTimeMillis())
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Recording, keyboardState)
    }

    @Test
    fun `toKeyboardState maps Paused to Recording`() {
        val recordingState = RecordingState.Paused(System.currentTimeMillis())
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Recording, keyboardState)
    }

    @Test
    fun `toKeyboardState maps Stopping to Transcribing`() {
        val recordingState = RecordingState.Stopping
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Transcribing, keyboardState)
    }

    @Test
    fun `toKeyboardState maps Error correctly`() {
        val errorMessage = "Microphone unavailable"
        val recordingState = RecordingState.Error(errorMessage)
        val keyboardState = recordingState.toKeyboardState()
        assertEquals(KeyboardState.Error(errorMessage), keyboardState)
    }
}
