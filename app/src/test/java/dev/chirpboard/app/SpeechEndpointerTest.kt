package dev.chirpboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the end-of-speech detection the system RecognitionService relies on for clients
 * that never call stopListening (IME-2): trailing silence ends an utterance, an
 * all-silence session times out, and speech start is reported exactly once (IME-20).
 */
class SpeechEndpointerTest {
    private companion object {
        const val SPEECH = 0.05f
        const val SILENCE = 0.001f
    }

    private fun endpointer(
        completeSilenceMs: Long = 2_000L,
        minimumUtteranceMs: Long = 0L,
        noSpeechTimeoutMs: Long = 5_000L,
    ) = SpeechEndpointer(
        completeSilenceMs = completeSilenceMs,
        minimumUtteranceMs = minimumUtteranceMs,
        noSpeechTimeoutMs = noSpeechTimeoutMs,
    )

    @Test
    fun `first speech frame reports speech started exactly once`() {
        val endpointer = endpointer()
        assertEquals(SpeechEndpointer.Event.SPEECH_STARTED, endpointer.onAmplitude(SPEECH, 0L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SPEECH, 100L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SPEECH, 200L))
    }

    @Test
    fun `trailing silence after speech ends the utterance`() {
        val endpointer = endpointer(completeSilenceMs = 2_000L)
        endpointer.onAmplitude(SPEECH, 0L)
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 1_000L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 1_999L))
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 2_000L))
    }

    @Test
    fun `speech resets the trailing silence window`() {
        val endpointer = endpointer(completeSilenceMs = 2_000L)
        endpointer.onAmplitude(SPEECH, 0L)
        endpointer.onAmplitude(SILENCE, 1_500L)
        // The user resumed speaking mid-pause: the silence clock restarts.
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SPEECH, 1_800L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 3_000L))
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 3_800L))
    }

    @Test
    fun `all-silence session times out with no-speech`() {
        val endpointer = endpointer(noSpeechTimeoutMs = 5_000L)
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 0L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 4_999L))
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(SILENCE, 5_000L))
    }

    @Test
    fun `no terminal event before the minimum utterance length`() {
        val endpointer =
            endpointer(
                completeSilenceMs = 1_000L,
                minimumUtteranceMs = 4_000L,
                noSpeechTimeoutMs = 2_000L,
            )
        endpointer.onAmplitude(SPEECH, 0L)
        // Trailing silence elapsed, but the client demanded a minimum utterance length.
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 2_000L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 3_999L))
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 4_000L))
    }

    @Test
    fun `terminal events fire at most once`() {
        val endpointer = endpointer(completeSilenceMs = 1_000L)
        endpointer.onAmplitude(SPEECH, 0L)
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 1_500L))
        // The session owner stops asynchronously; further frames must stay silent.
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 3_000L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SPEECH, 3_100L))
    }

    @Test
    fun `no-speech timeout never fires once speech was detected`() {
        val endpointer = endpointer(completeSilenceMs = 10_000L, noSpeechTimeoutMs = 5_000L)
        endpointer.onAmplitude(SPEECH, 0L)
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 6_000L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 9_999L))
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 10_000L))
    }
}
