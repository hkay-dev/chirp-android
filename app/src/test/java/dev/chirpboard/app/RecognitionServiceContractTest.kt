package dev.chirpboard.app

import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SpeechRecognizer/RecognizerIntent contract mappings of the system recognition
 * surfaces (IME-7/IME-15/IME-19/LIF-09, TST-005): language support, activity result
 * codes, and the RMS dB conversion.
 */
class RecognitionServiceContractTest {
    // --- IME-15/PIPE-08: language support ---

    @Test
    fun `english variants are supported in both tag forms`() {
        assertTrue(isRecognitionLanguageSupported("en-US"))
        assertTrue(isRecognitionLanguageSupported("en_GB"))
        assertTrue(isRecognitionLanguageSupported("en"))
        assertTrue(isRecognitionLanguageSupported("en-IN"))
    }

    @Test
    fun `non-english requests are rejected`() {
        assertFalse(isRecognitionLanguageSupported("es-ES"))
        assertFalse(isRecognitionLanguageSupported("de_DE"))
        assertFalse(isRecognitionLanguageSupported("fr"))
        assertFalse(isRecognitionLanguageSupported("zh-Hans-CN"))
    }

    @Test
    fun `missing or blank language requests default to supported`() {
        assertTrue(isRecognitionLanguageSupported(null))
        assertTrue(isRecognitionLanguageSupported(""))
        assertTrue(isRecognitionLanguageSupported("   "))
    }

    // --- LIF-09/IME-9: SpeechRecognizer error -> RecognizerIntent activity result code ---

    @Test
    fun `no-match and speech timeout map to the no-match result`() {
        assertEquals(
            RecognizerIntent.RESULT_NO_MATCH,
            recognizerIntentResultCodeFor(SpeechRecognizer.ERROR_NO_MATCH),
        )
        assertEquals(
            RecognizerIntent.RESULT_NO_MATCH,
            recognizerIntentResultCodeFor(SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
    }

    @Test
    fun `audio errors map to the audio-error result`() {
        assertEquals(
            RecognizerIntent.RESULT_AUDIO_ERROR,
            recognizerIntentResultCodeFor(SpeechRecognizer.ERROR_AUDIO),
        )
    }

    @Test
    fun `server errors map to the server-error result`() {
        assertEquals(
            RecognizerIntent.RESULT_SERVER_ERROR,
            recognizerIntentResultCodeFor(SpeechRecognizer.ERROR_SERVER),
        )
    }

    @Test
    fun `busy client and permission errors map to the client-error result`() {
        assertEquals(
            RecognizerIntent.RESULT_CLIENT_ERROR,
            recognizerIntentResultCodeFor(SpeechRecognizer.ERROR_RECOGNIZER_BUSY),
        )
        assertEquals(
            RecognizerIntent.RESULT_CLIENT_ERROR,
            recognizerIntentResultCodeFor(SpeechRecognizer.ERROR_CLIENT),
        )
        assertEquals(
            RecognizerIntent.RESULT_CLIENT_ERROR,
            recognizerIntentResultCodeFor(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
    }

    // --- IME-19: RMS dB conversion ---

    @Test
    fun `rms conversion spans the de-facto range`() {
        assertEquals(-2f, amplitudeToRmsDb(0f), 0.001f)
        assertEquals(10f, amplitudeToRmsDb(1f), 0.001f)
    }

    @Test
    fun `rms conversion is monotonic and never saturates for normal speech`() {
        val quiet = amplitudeToRmsDb(0.005f)
        val speech = amplitudeToRmsDb(0.05f)
        val loud = amplitudeToRmsDb(0.3f)
        assertTrue(quiet < speech)
        assertTrue(speech < loud)
        // Typical speech must not peg the client's mic animation at max (the old 0..100
        // scaling did exactly that).
        assertTrue(loud < 10f)
    }
}
