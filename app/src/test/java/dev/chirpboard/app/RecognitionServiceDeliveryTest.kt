package dev.chirpboard.app

import android.speech.SpeechRecognizer
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * TST-005: pins ChirpRecognitionService's transcribe-and-deliver decision — the side of the
 * SpeechRecognizer contract that external clients (e.g. SwiftKey) actually observe. One case
 * per precondition and per failed [TranscriptionOutcome], plus the single-hypothesis success
 * delivery and the precondition short-circuits that must never run the decoder.
 */
class RecognitionServiceDeliveryTest {
    @Test
    fun `empty capture delivers benign no-match and never invokes the decoder`() =
        runTest {
            var transcribed = false

            val delivery =
                resolveServiceRecognitionDelivery(
                    samplesEmpty = true,
                    recognizerReady = true,
                ) {
                    transcribed = true
                    TranscriptionOutcome.Success(text = "never", wordTimings = null)
                }

            assertEquals(
                SpeechRecognizer.ERROR_NO_MATCH,
                (delivery as ServiceRecognitionDelivery.Error).errorCode,
            )
            assertFalse(transcribed)
        }

    @Test
    fun `recognizer not ready delivers a server error and never invokes the decoder`() =
        runTest {
            var transcribed = false

            val delivery =
                resolveServiceRecognitionDelivery(
                    samplesEmpty = false,
                    recognizerReady = false,
                ) {
                    transcribed = true
                    TranscriptionOutcome.Success(text = "never", wordTimings = null)
                }

            assertEquals(
                SpeechRecognizer.ERROR_SERVER,
                (delivery as ServiceRecognitionDelivery.Error).errorCode,
            )
            assertFalse(transcribed)
        }

    @Test
    fun `silent decode delivers no-match not an audio failure`() =
        runTest {
            val delivery =
                resolveServiceRecognitionDelivery(
                    samplesEmpty = false,
                    recognizerReady = true,
                ) { TranscriptionOutcome.NoSpeech }

            assertEquals(
                SpeechRecognizer.ERROR_NO_MATCH,
                (delivery as ServiceRecognitionDelivery.Error).errorCode,
            )
        }

    @Test
    fun `model unavailable delivers a server error`() =
        runTest {
            val delivery =
                resolveServiceRecognitionDelivery(
                    samplesEmpty = false,
                    recognizerReady = true,
                ) { TranscriptionOutcome.ModelUnavailable("evicted under memory pressure") }

            assertEquals(
                SpeechRecognizer.ERROR_SERVER,
                (delivery as ServiceRecognitionDelivery.Error).errorCode,
            )
        }

    @Test
    fun `engine failure delivers a server error regardless of retryability`() =
        runTest {
            for (retryable in listOf(true, false)) {
                val delivery =
                    resolveServiceRecognitionDelivery(
                        samplesEmpty = false,
                        recognizerReady = true,
                    ) { TranscriptionOutcome.EngineError(reason = "decode failed", retryable = retryable) }

                assertEquals(
                    SpeechRecognizer.ERROR_SERVER,
                    (delivery as ServiceRecognitionDelivery.Error).errorCode,
                )
            }
        }

    @Test
    fun `successful decode delivers exactly the transcribed text as the single hypothesis`() =
        runTest {
            val delivery =
                resolveServiceRecognitionDelivery(
                    samplesEmpty = false,
                    recognizerReady = true,
                ) { TranscriptionOutcome.Success(text = "hello world", wordTimings = null) }

            assertEquals(
                ServiceRecognitionDelivery.Results(text = "hello world"),
                delivery,
            )
        }
}
