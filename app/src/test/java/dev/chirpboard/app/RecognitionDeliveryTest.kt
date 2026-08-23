package dev.chirpboard.app

import android.speech.SpeechRecognizer
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionDeliveryTest {
    @Test
    fun `committed text on idle phase is delivered`() {
        val delivery =
            resolveRecognitionDelivery(
                committedText = "hello world",
                processedText = null,
                terminalPhase = InlineTranscriptionPhase.Idle,
            )

        assertTrue(delivery is RecognitionDelivery.Success)
        assertEquals("hello world", (delivery as RecognitionDelivery.Success).text)
    }

    @Test
    fun `polished text is preferred when polish succeeded`() {
        // The caller already waited for the polish stage; delivering raw would silently
        // discard the AI result the user enabled.
        val delivery =
            resolveRecognitionDelivery(
                committedText = "hello world",
                processedText = "Hello, world.",
                terminalPhase = InlineTranscriptionPhase.Idle,
            )

        assertTrue(delivery is RecognitionDelivery.Success)
        assertEquals("Hello, world.", (delivery as RecognitionDelivery.Success).text)
    }

    @Test
    fun `raw text is delivered when polish ended in LlmError even if a processed text exists`() {
        // The opening-content guard persists the rejected AI text but must not deliver it.
        val delivery =
            resolveRecognitionDelivery(
                committedText = "please remember the release",
                processedText = "The release.",
                terminalPhase = InlineTranscriptionPhase.LlmError("AI dropped opening content"),
            )

        assertTrue(delivery is RecognitionDelivery.Success)
        assertEquals("please remember the release", (delivery as RecognitionDelivery.Success).text)
    }

    @Test
    fun `blank polished text falls back to committed raw text`() {
        val delivery =
            resolveRecognitionDelivery(
                committedText = "hello world",
                processedText = "   ",
                terminalPhase = InlineTranscriptionPhase.Idle,
            )

        assertTrue(delivery is RecognitionDelivery.Success)
        assertEquals("hello world", (delivery as RecognitionDelivery.Success).text)
    }

    @Test
    fun `committed raw text is delivered even when LLM polish failed`() {
        // Regression: an LLM-polish failure must not discard the user's transcript.
        val delivery =
            resolveRecognitionDelivery(
                committedText = "this is another test",
                processedText = null,
                terminalPhase = InlineTranscriptionPhase.LlmError("LLM failed: no api key"),
            )

        assertTrue(delivery is RecognitionDelivery.Success)
        assertEquals("this is another test", (delivery as RecognitionDelivery.Success).text)
    }

    @Test
    fun `transcription failure with no committed text returns client error`() {
        val delivery =
            resolveRecognitionDelivery(
                committedText = "",
                processedText = null,
                terminalPhase = InlineTranscriptionPhase.Error("decode failed"),
            )

        assertTrue(delivery is RecognitionDelivery.Failure)
        assertEquals(SpeechRecognizer.ERROR_CLIENT, (delivery as RecognitionDelivery.Failure).errorCode)
    }

    @Test
    fun `blank result on the no-speech phase returns no-match`() {
        // IME-16: the keyboard's NoSpeech terminal phase is not an error; the dialog
        // maps it to the same no-match retry state a blank Idle resolution gets.
        val delivery =
            resolveRecognitionDelivery(
                committedText = "",
                processedText = null,
                terminalPhase = InlineTranscriptionPhase.NoSpeech,
            )

        assertTrue(delivery is RecognitionDelivery.Failure)
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, (delivery as RecognitionDelivery.Failure).errorCode)
    }

    @Test
    fun `blank result on idle phase returns no-match`() {
        val delivery =
            resolveRecognitionDelivery(
                committedText = "   ",
                processedText = null,
                terminalPhase = InlineTranscriptionPhase.Idle,
            )

        assertTrue(delivery is RecognitionDelivery.Failure)
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, (delivery as RecognitionDelivery.Failure).errorCode)
    }
}
