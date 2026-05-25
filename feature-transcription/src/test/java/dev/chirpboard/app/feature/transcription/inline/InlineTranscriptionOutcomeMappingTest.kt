package dev.chirpboard.app.feature.transcription.inline

import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineTranscriptionOutcomeMappingTest {
    @Test
    fun `success with text maps to Success`() {
        val result = mapInlineTranscriptionOutcome(TranscriptionOutcome.Success("hello"))
        assertTrue(result is InlineTranscriptionResolution.Success)
        assertEquals("hello", (result as InlineTranscriptionResolution.Success).text)
    }

    @Test
    fun `blank success maps to NoSpeech`() {
        val result = mapInlineTranscriptionOutcome(TranscriptionOutcome.Success("  "))
        assertEquals(InlineTranscriptionResolution.NoSpeech, result)
    }

    @Test
    fun `NoSpeech maps to NoSpeech`() {
        val result = mapInlineTranscriptionOutcome(TranscriptionOutcome.NoSpeech)
        assertEquals(InlineTranscriptionResolution.NoSpeech, result)
    }

    @Test
    fun `ModelUnavailable maps to Failure`() {
        val result = mapInlineTranscriptionOutcome(TranscriptionOutcome.ModelUnavailable("missing"))
        assertTrue(result is InlineTranscriptionResolution.Failure)
    }
}
