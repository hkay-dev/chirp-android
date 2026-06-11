package dev.chirpboard.app

import android.speech.SpeechRecognizer
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionDeliveryTest {
    @Test
    fun `committed text on idle phase is delivered`() {
        val delivery = resolveRecognitionDelivery("hello world", InlineTranscriptionPhase.Idle)

        assertTrue(delivery is RecognitionDelivery.Success)
        assertEquals("hello world", (delivery as RecognitionDelivery.Success).text)
    }

    @Test
    fun `committed raw text is delivered even when LLM polish failed`() {
        // Regression: an LLM-polish failure must not discard the user's transcript.
        val delivery =
            resolveRecognitionDelivery(
                committedText = "this is another test",
                terminalPhase = InlineTranscriptionPhase.LlmError("LLM failed: no api key"),
            )

        assertTrue(delivery is RecognitionDelivery.Success)
        assertEquals("this is another test", (delivery as RecognitionDelivery.Success).text)
    }

    @Test
    fun `transcription failure with no committed text returns client error`() {
        val delivery = resolveRecognitionDelivery("", InlineTranscriptionPhase.Error("decode failed"))

        assertTrue(delivery is RecognitionDelivery.Failure)
        assertEquals(SpeechRecognizer.ERROR_CLIENT, (delivery as RecognitionDelivery.Failure).errorCode)
    }

    @Test
    fun `blank result on idle phase returns no-match`() {
        val delivery = resolveRecognitionDelivery("   ", InlineTranscriptionPhase.Idle)

        assertTrue(delivery is RecognitionDelivery.Failure)
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, (delivery as RecognitionDelivery.Failure).errorCode)
    }
}
