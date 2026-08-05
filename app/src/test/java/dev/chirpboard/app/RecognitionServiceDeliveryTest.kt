package dev.chirpboard.app

import android.speech.SpeechRecognizer
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the RecognitionService callback mapping after the process-owned runner settles. */
class RecognitionServiceDeliveryTest {
    @Test
    fun `successful runner outcome delivers one transcript hypothesis`() {
        val delivery =
            resolveServiceRecognitionDelivery(
                committedText = "hello world",
                terminalPhase = InlineTranscriptionPhase.Idle,
                recognizerReady = true,
            )

        assertEquals(ServiceRecognitionDelivery.Results("hello world"), delivery)
    }

    @Test
    fun `blank successful outcome delivers benign no-match`() {
        val delivery =
            resolveServiceRecognitionDelivery(
                committedText = "",
                terminalPhase = InlineTranscriptionPhase.Idle,
                recognizerReady = true,
            )

        assertEquals(
            SpeechRecognizer.ERROR_NO_MATCH,
            (delivery as ServiceRecognitionDelivery.Error).errorCode,
        )
    }

    @Test
    fun `failed runner outcome delivers server error when recognizer stayed ready`() {
        val delivery =
            resolveServiceRecognitionDelivery(
                committedText = "",
                terminalPhase = InlineTranscriptionPhase.Error("decode failed"),
                recognizerReady = true,
            )

        assertEquals(
            SpeechRecognizer.ERROR_SERVER,
            (delivery as ServiceRecognitionDelivery.Error).errorCode,
        )
    }

    @Test
    fun `failed runner outcome delivers server error when model is unavailable`() {
        val delivery =
            resolveServiceRecognitionDelivery(
                committedText = "",
                terminalPhase = InlineTranscriptionPhase.Error("model unavailable"),
                recognizerReady = false,
            )

        assertEquals(
            SpeechRecognizer.ERROR_SERVER,
            (delivery as ServiceRecognitionDelivery.Error).errorCode,
        )
    }
}
