package dev.chirpboard.app

/**
 * Amplitude-driven end-of-speech detector for the system [ChirpRecognitionService] (IME-2).
 *
 * The RecognitionService contract expects the service to detect end of speech and deliver
 * results (or [android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT]) on its own;
 * `stopListening` is optional and many clients never call it. This endpointer consumes the
 * recorder's existing ~15 Hz mean-abs amplitude stream and emits at most one terminal event
 * per session:
 *
 *  - [Event.SPEECH_STARTED] the first time the amplitude crosses the speech threshold
 *    (drives `beginningOfSpeech`, which must reflect detected speech — IME-20);
 *  - [Event.END_OF_SPEECH] once speech was detected and the trailing silence reaches
 *    [completeSilenceMs] (honors `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`);
 *  - [Event.NO_SPEECH_TIMEOUT] when no speech is ever detected within [noSpeechTimeoutMs].
 *
 * No terminal event fires before [minimumUtteranceMs] has elapsed since the first sample
 * (honors `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS`). After a terminal event the
 * endpointer stays silent; the session owner is responsible for the actual stop, which the
 * coordinator's generation token already makes idempotent against a racing manual stop.
 *
 * Pure and clock-agnostic: callers pass a monotonic timestamp with every amplitude frame.
 */
internal class SpeechEndpointer(
    private val completeSilenceMs: Long = DEFAULT_COMPLETE_SILENCE_MS,
    private val minimumUtteranceMs: Long = 0L,
    private val noSpeechTimeoutMs: Long = DEFAULT_NO_SPEECH_TIMEOUT_MS,
    private val speechAmplitudeThreshold: Float = DEFAULT_SPEECH_AMPLITUDE_THRESHOLD,
) {
    internal enum class Event {
        NONE,
        SPEECH_STARTED,
        END_OF_SPEECH,
        NO_SPEECH_TIMEOUT,
    }

    private var sessionStartMs: Long? = null
    private var lastSpeechMs: Long? = null
    private var finished = false

    /**
     * Feed one amplitude frame (mean-abs of the capture buffer, 0..1) observed at
     * monotonic [nowMs]. Returns the event this frame triggered, [Event.NONE] otherwise.
     */
    fun onAmplitude(
        amplitude: Float,
        nowMs: Long,
    ): Event {
        if (finished) {
            return Event.NONE
        }
        val startMs = sessionStartMs ?: nowMs.also { sessionStartMs = it }

        if (amplitude >= speechAmplitudeThreshold) {
            val firstSpeech = lastSpeechMs == null
            lastSpeechMs = nowMs
            return if (firstSpeech) Event.SPEECH_STARTED else Event.NONE
        }

        if (nowMs - startMs < minimumUtteranceMs) {
            return Event.NONE
        }

        val speechMs = lastSpeechMs
        return when {
            speechMs == null && nowMs - startMs >= noSpeechTimeoutMs -> terminal(Event.NO_SPEECH_TIMEOUT)
            speechMs != null && nowMs - speechMs >= completeSilenceMs -> terminal(Event.END_OF_SPEECH)
            else -> Event.NONE
        }
    }

    private fun terminal(event: Event): Event {
        finished = true
        return event
    }

    companion object {
        /** Default trailing silence that ends an utterance, matching platform engines (~2s). */
        const val DEFAULT_COMPLETE_SILENCE_MS = 2_000L

        /** Default window in which some speech must be detected before ERROR_SPEECH_TIMEOUT. */
        const val DEFAULT_NO_SPEECH_TIMEOUT_MS = 5_000L

        /**
         * Mean-abs amplitude (at gain 1.0) above which a frame counts as speech. Quiet-room
         * noise sits around 0.001-0.005 on phone mics; voiced speech around 0.02-0.15.
         */
        const val DEFAULT_SPEECH_AMPLITUDE_THRESHOLD = 0.01f

        /** Bounds applied to client-provided silence/minimum-length extras. */
        const val MIN_CLIENT_SILENCE_MS = 500L
        const val MAX_CLIENT_SILENCE_MS = 30_000L
        const val MAX_CLIENT_MINIMUM_LENGTH_MS = 60_000L
    }
}
