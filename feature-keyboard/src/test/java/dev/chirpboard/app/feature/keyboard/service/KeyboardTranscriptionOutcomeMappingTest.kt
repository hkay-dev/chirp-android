package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTranscriptionOutcomeMappingTest {

    @Test
    fun `success with non-blank text maps to success`() {
        val result = mapKeyboardTranscriptionOutcome(TranscriptionOutcome.Success("hello"))

        assertTrue(result is KeyboardTranscriptionResolution.Success)
        assertEquals("hello", (result as KeyboardTranscriptionResolution.Success).text)
    }

    @Test
    fun `success with blank text maps to no speech`() {
        val result = mapKeyboardTranscriptionOutcome(TranscriptionOutcome.Success("   "))

        assertEquals(KeyboardTranscriptionResolution.NoSpeech, result)
    }

    @Test
    fun `no speech outcome maps to no speech`() {
        val result = mapKeyboardTranscriptionOutcome(TranscriptionOutcome.NoSpeech)

        assertEquals(KeyboardTranscriptionResolution.NoSpeech, result)
    }

    @Test
    fun `model unavailable maps to failure`() {
        val result = mapKeyboardTranscriptionOutcome(
            TranscriptionOutcome.ModelUnavailable("model is not downloaded")
        )

        assertTrue(result is KeyboardTranscriptionResolution.Failure)
        assertTrue((result as KeyboardTranscriptionResolution.Failure).message.contains("Recognizer unavailable"))
    }

    @Test
    fun `engine error maps to failure`() {
        val result = mapKeyboardTranscriptionOutcome(
            TranscriptionOutcome.EngineError("decoder crashed", retryable = false)
        )

        assertTrue(result is KeyboardTranscriptionResolution.Failure)
        assertTrue((result as KeyboardTranscriptionResolution.Failure).message.contains("Transcription engine failed"))
    }
}
