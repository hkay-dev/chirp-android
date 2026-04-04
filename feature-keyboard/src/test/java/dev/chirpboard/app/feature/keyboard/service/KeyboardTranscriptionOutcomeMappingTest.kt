package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `successful persistence plan keeps transcript and completed status`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "hello from the keyboard",
            processedText = "Hello from the keyboard.",
            errorMessage = null
        )

        assertEquals(RecordingStatus.COMPLETED, plan.status)
        assertEquals("hello from the keyboard", plan.title)
        assertEquals("hello from the keyboard", plan.rawText)
        assertEquals("Hello from the keyboard.", plan.processedText)
        assertNull(plan.errorMessage)
    }

    @Test
    fun `failure persistence plan keeps audio and marks recording failed`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = null,
            processedText = "ignored",
            errorMessage = "Recognizer not ready"
        )

        assertEquals(RecordingStatus.FAILED, plan.status)
        assertEquals("Recognizer not ready", plan.title)
        assertNull(plan.rawText)
        assertNull(plan.processedText)
        assertEquals("Recognizer not ready", plan.errorMessage)
    }
}
