package dev.chirpboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        /**
         * Mild ambient room noise, amplified by the user's microphone-gain multiplier,
         * sitting just above the speech threshold — the live-hang signal: small but
         * non-zero RMS that the amplitude heuristic mistakes for speech.
         */
        const val AMBIENT_NOISE = 0.012f
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

    /**
     * Pumps the *varying* ambient-noise pattern observed on-device (the dialog's waveform
     * "small varying dots"): frames alternate just above and just below the speech threshold
     * at a realistic ~15 Hz cadence, so a flicker never sustains long enough to be mistaken
     * for genuine speech. Drives [endpointer] from [fromMs] up to but not including [toMs],
     * asserting no terminal event fires within the window.
     */
    private fun SpeechEndpointer.pumpVaryingAmbientNoise(
        fromMs: Long,
        toMs: Long,
        stepMs: Long = 66L,
    ) {
        var nowMs = fromMs
        var above = true
        while (nowMs < toMs) {
            val event = onAmplitude(if (above) AMBIENT_NOISE else SILENCE, nowMs)
            assertTrue(
                "ambient frame at $nowMs must not produce a terminal event before the cap: $event",
                event == SpeechEndpointer.Event.NONE || event == SpeechEndpointer.Event.SPEECH_STARTED,
            )
            above = !above
            nowMs += stepMs
        }
    }

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
    fun `no-speech timeout never fires once sustained speech was detected`() {
        // Once a sustained above-threshold run establishes a speech session, the no-speech
        // cap is disabled and the session ends only on the trailing-silence END_OF_SPEECH
        // (here a long client complete-silence window) — never cut short as no-speech.
        val endpointer = endpointer(completeSilenceMs = 10_000L, noSpeechTimeoutMs = 5_000L)
        assertEquals(SpeechEndpointer.Event.SPEECH_STARTED, endpointer.onAmplitude(SPEECH, 0L))
        // A few consecutive voiced frames sustain past the establish window (~300ms).
        endpointer.onAmplitude(SPEECH, 100L)
        endpointer.onAmplitude(SPEECH, 200L)
        endpointer.onAmplitude(SPEECH, 400L)
        // Past the no-speech budget (5s) but speech is established, so the cap is disabled.
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 6_000L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 9_999L))
        // Ends on the client's trailing-silence window measured from the last voiced frame.
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 10_400L))
    }

    // --- Absolute no-speech cap: ambient noise must not defeat termination (live hang) ---

    @Test
    fun `ambient noise above the threshold still times out at the absolute cap`() {
        // Live regression: mild room noise (amplified by mic gain) drifts above the speech
        // threshold in varying flickers, so the old `lastSpeechMs == null` no-speech branch
        // could never fire and the session listened forever. With realistic varying noise
        // (no flicker sustains long enough to be real speech), the amplitude-independent
        // absolute cap must still terminate the session.
        val endpointer = endpointer(completeSilenceMs = 2_000L, noSpeechTimeoutMs = 10_000L)
        endpointer.pumpVaryingAmbientNoise(fromMs = 0L, toMs = 10_000L)
        // The absolute cap is reached: no flicker ever sustained into a real utterance, so
        // the session times out as no-speech regardless of the surrounding ambient level.
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(SILENCE, 10_000L))
    }

    @Test
    fun `ambient noise on the very frame at the cap still times out`() {
        // The cap is independent of amplitude: it keys on whether *sustained* speech was
        // established, not on this frame's level. So even an above-threshold ambient flicker
        // landing exactly on the cap (with no sustained run before it) times out as
        // no-speech, never END_OF_SPEECH.
        val endpointer = endpointer(completeSilenceMs = 2_000L, noSpeechTimeoutMs = 10_000L)
        endpointer.pumpVaryingAmbientNoise(fromMs = 0L, toMs = 10_000L)
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(AMBIENT_NOISE, 10_000L))
    }

    @Test
    fun `continuous speech past the no-speech budget is not cut short`() {
        // A user who keeps speaking past the 10s no-speech budget is mid-utterance, not a
        // no-speech session: the absolute cap must NOT fire while speech is ongoing. They
        // end on the trailing-silence END_OF_SPEECH once they pause.
        val endpointer = endpointer(completeSilenceMs = 2_000L, noSpeechTimeoutMs = 10_000L)
        var nowMs = 0L
        // Continuous speech well past the no-speech budget (frames < completeSilence apart).
        while (nowMs <= 15_000L) {
            val event = endpointer.onAmplitude(SPEECH, nowMs)
            val expected = if (nowMs == 0L) SpeechEndpointer.Event.SPEECH_STARTED else SpeechEndpointer.Event.NONE
            assertEquals("speaking frame at $nowMs must not be cut short", expected, event)
            nowMs += 500L
        }
        // The user pauses; the utterance ends on its trailing silence, NOT as a no-speech
        // timeout — their words are delivered, not dropped into a "didn't catch anything".
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 16_000L))
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 17_000L))
    }

    @Test
    fun `speech that finishes before the budget ends on trailing silence not the cap`() {
        // A short utterance that completes (trailing silence) before the no-speech budget
        // must surface END_OF_SPEECH, never the no-speech timeout.
        val endpointer = endpointer(completeSilenceMs = 2_000L, noSpeechTimeoutMs = 10_000L)
        endpointer.onAmplitude(SPEECH, 0L)
        endpointer.onAmplitude(SPEECH, 1_000L)
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 2_000L))
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 3_000L))
    }

    // --- recognizerSessionEndpointer: the shared per-session config both surfaces use ---

    @Test
    fun `default session terminates an all-silence capture within the 10s budget`() {
        // Regression for the live no-speech hang: with no client extras, a session in
        // which speech never starts must still reach a terminal within ~8-12s.
        val endpointer = recognizerSessionEndpointer(clientCompleteSilenceMs = null, clientMinimumLengthMs = null)
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 0L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 9_999L))
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(SILENCE, 10_000L))
    }

    @Test
    fun `default session keeps the 2s trailing-silence endpointing after speech`() {
        val endpointer = recognizerSessionEndpointer(clientCompleteSilenceMs = null, clientMinimumLengthMs = null)
        assertEquals(SpeechEndpointer.Event.SPEECH_STARTED, endpointer.onAmplitude(SPEECH, 0L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 1_999L))
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 2_000L))
    }

    @Test
    fun `client complete-silence longer than the default raises the no-speech budget`() {
        // A caller that tolerates 20s pauses must not be cut off after 10s of leading silence.
        val endpointer = recognizerSessionEndpointer(clientCompleteSilenceMs = 20_000L, clientMinimumLengthMs = null)
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 0L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 19_999L))
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(SILENCE, 20_000L))
    }

    @Test
    fun `client minimum length delays the no-speech timeout`() {
        // EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS: no terminal before the client's minimum.
        val endpointer = recognizerSessionEndpointer(clientCompleteSilenceMs = null, clientMinimumLengthMs = 30_000L)
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 0L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 29_999L))
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(SILENCE, 30_000L))
    }

    @Test
    fun `client silence extras are clamped to sane bounds`() {
        // A 1ms complete-silence request clamps up to 500ms instead of ending the
        // utterance on the first quiet frame.
        val endpointer = recognizerSessionEndpointer(clientCompleteSilenceMs = 1L, clientMinimumLengthMs = null)
        endpointer.onAmplitude(SPEECH, 0L)
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 100L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 499L))
        assertEquals(SpeechEndpointer.Event.END_OF_SPEECH, endpointer.onAmplitude(SILENCE, 500L))
    }

    @Test
    fun `client minimum length is clamped so a session cannot be made unterminable`() {
        val endpointer =
            recognizerSessionEndpointer(
                clientCompleteSilenceMs = null,
                clientMinimumLengthMs = Long.MAX_VALUE,
            )
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 0L))
        assertEquals(SpeechEndpointer.Event.NONE, endpointer.onAmplitude(SILENCE, 59_999L))
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(SILENCE, 60_000L))
    }

    @Test
    fun `default session times out an ambient-noise capture at the absolute cap`() {
        // The live hang, reproduced through the shared per-session config: varying ambient
        // noise must still hit the no-speech cap at the default 10s budget.
        val endpointer = recognizerSessionEndpointer(clientCompleteSilenceMs = null, clientMinimumLengthMs = null)
        endpointer.pumpVaryingAmbientNoise(fromMs = 0L, toMs = 10_000L)
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(SILENCE, 10_000L))
    }

    @Test
    fun `client complete-silence override raises the ambient-noise cap to match`() {
        // EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS: a caller that tolerates 20s
        // pauses raises the no-speech budget, so an ambient-noise session is not cut at 10s
        // but is still terminated at the (raised) cap rather than listening forever.
        val endpointer = recognizerSessionEndpointer(clientCompleteSilenceMs = 20_000L, clientMinimumLengthMs = null)
        endpointer.pumpVaryingAmbientNoise(fromMs = 0L, toMs = 20_000L)
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(SILENCE, 20_000L))
    }

    @Test
    fun `client minimum length override delays the ambient-noise cap`() {
        // EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS holds back every terminal — including the
        // absolute cap — so an ambient-noise session is not timed out before the client's
        // minimum utterance length, then is.
        val endpointer = recognizerSessionEndpointer(clientCompleteSilenceMs = null, clientMinimumLengthMs = 30_000L)
        endpointer.pumpVaryingAmbientNoise(fromMs = 0L, toMs = 30_000L)
        assertEquals(SpeechEndpointer.Event.NO_SPEECH_TIMEOUT, endpointer.onAmplitude(SILENCE, 30_000L))
    }
}
