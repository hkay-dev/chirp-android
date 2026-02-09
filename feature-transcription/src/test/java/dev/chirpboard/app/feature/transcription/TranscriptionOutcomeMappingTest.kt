package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptionOutcomeMappingTest {

    @Test
    fun `success outcome returns text`() {
        val result = mapOutcomeForChunkTranscription(TranscriptionOutcome.Success("hello world"))

        assertEquals("hello world", result)
    }

    @Test
    fun `no speech outcome returns empty chunk`() {
        val result = mapOutcomeForChunkTranscription(TranscriptionOutcome.NoSpeech)

        assertEquals("", result)
    }

    @Test
    fun `model unavailable outcome throws non-retryable exception`() {
        assertThrows(NonRetryableTranscriptionException::class.java) {
            mapOutcomeForChunkTranscription(
                TranscriptionOutcome.ModelUnavailable("model missing")
            )
        }
    }

    @Test
    fun `retryable engine error outcome throws retryable exception`() {
        assertThrows(RetryableTranscriptionException::class.java) {
            mapOutcomeForChunkTranscription(
                TranscriptionOutcome.EngineError("temporary codec issue", retryable = true)
            )
        }
    }

    @Test
    fun `non retryable engine error outcome throws non-retryable exception`() {
        assertThrows(NonRetryableTranscriptionException::class.java) {
            mapOutcomeForChunkTranscription(
                TranscriptionOutcome.EngineError("decoder crashed", retryable = false)
            )
        }
    }
}
