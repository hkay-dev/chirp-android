package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardRecognitionModelsTest {
    @Test
    fun `mapKeyboardTranscriptionOutcome returns Success on valid text`() {
        val result = mapKeyboardTranscriptionOutcome(TranscriptionOutcome.Success("hello"))
        assertTrue(result is KeyboardTranscriptionResolution.Success)
        assertEquals("hello", (result as KeyboardTranscriptionResolution.Success).text)
    }

    @Test
    fun `mapKeyboardTranscriptionOutcome returns NoSpeech on blank text`() {
        val result = mapKeyboardTranscriptionOutcome(TranscriptionOutcome.Success("   "))
        assertTrue(result is KeyboardTranscriptionResolution.NoSpeech)
    }

    @Test
    fun `mapKeyboardTranscriptionOutcome returns NoSpeech on NoSpeech outcome`() {
        val result = mapKeyboardTranscriptionOutcome(TranscriptionOutcome.NoSpeech)
        assertTrue(result is KeyboardTranscriptionResolution.NoSpeech)
    }

    @Test
    fun `mapKeyboardTranscriptionOutcome returns Failure on ModelUnavailable`() {
        val result = mapKeyboardTranscriptionOutcome(TranscriptionOutcome.ModelUnavailable("Not downloaded"))
        assertTrue(result is KeyboardTranscriptionResolution.Failure)
        assertEquals("Recognizer unavailable: Not downloaded", (result as KeyboardTranscriptionResolution.Failure).message)
    }

    @Test
    fun `mapKeyboardTranscriptionOutcome returns Failure on EngineError`() {
        val result = mapKeyboardTranscriptionOutcome(TranscriptionOutcome.EngineError("Crash"))
        assertTrue(result is KeyboardTranscriptionResolution.Failure)
        assertEquals("Transcription engine failed: Crash", (result as KeyboardTranscriptionResolution.Failure).message)
    }
}
