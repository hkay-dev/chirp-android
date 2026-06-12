package dev.chirpboard.app

import kotlinx.coroutines.delay
import kotlin.math.max

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
 *  - [Event.NO_SPEECH_TIMEOUT] when no utterance is ever *completed* within the
 *    no-speech budget.
 *
 * ## Why amplitude alone is not enough (the live no-speech hang)
 *
 * This is a NON-STREAMING offline recognizer: it cannot tell ambient room noise from
 * speech until the capture is stopped and transcribed. The amplitude path is therefore a
 * heuristic fast-path only. In a room with mild ambient noise the recorder's mean-abs
 * amplitude — amplified by the user's microphone-gain multiplier — drifts above
 * [speechAmplitudeThreshold], so [lastSpeechMs] gets set even though nobody spoke. Once
 * that happens the no-speech branch (which keyed on `lastSpeechMs == null`) could never
 * fire again, and intermittent noise kept resetting the trailing-silence window, so neither
 * terminal event was reachable and the session listened indefinitely.
 *
 * The backstop is an ABSOLUTE no-speech cap that is independent of amplitude: if no
 * utterance has been *completed* ([Event.END_OF_SPEECH] never fired) within
 * [noSpeechTimeoutMs] of the first frame, the session terminates with
 * [Event.NO_SPEECH_TIMEOUT] regardless of how loud the ambient noise is. Amplitude still
 * provides the fast path — a genuinely quiet room ends a never-started session at the same
 * budget, and a real utterance ends sooner on its trailing silence — but ambient noise can
 * no longer defeat termination.
 *
 * No terminal event fires before [minimumUtteranceMs] has elapsed since the first sample
 * (honors `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS`). After a terminal event the
 * endpointer stays silent; the session owner is responsible for the actual stop, which the
 * coordinator's generation token already makes idempotent against a racing manual stop.
 *
 * Pure and clock-agnostic: callers pass a monotonic timestamp with every amplitude frame.
 * That also makes it event-driven only — a capture whose reads stall entirely never
 * advances it — so the session owners pair it with the wall-clock
 * [awaitRecognitionCaptureStall] companion (MIC-018).
 */
internal class SpeechEndpointer(
    private val completeSilenceMs: Long = DEFAULT_COMPLETE_SILENCE_MS,
    private val minimumUtteranceMs: Long = 0L,
    private val noSpeechTimeoutMs: Long = DEFAULT_NO_SPEECH_TIMEOUT_MS,
    private val speechAmplitudeThreshold: Float = DEFAULT_SPEECH_AMPLITUDE_THRESHOLD,
    private val minSustainedSpeechMs: Long = DEFAULT_MIN_SUSTAINED_SPEECH_MS,
) {
    internal enum class Event {
        NONE,
        SPEECH_STARTED,
        END_OF_SPEECH,
        NO_SPEECH_TIMEOUT,
    }

    private var sessionStartMs: Long? = null
    private var lastSpeechMs: Long? = null

    /** Start of the current contiguous above-threshold run; null while below threshold. */
    private var speechRunStartMs: Long? = null

    /**
     * True once an above-threshold run has lasted at least [minSustainedSpeechMs] — the
     * point at which the input is treated as a genuine *speech session* rather than ambient
     * flicker. Once set, the no-speech cap is disabled: the session can only end on the
     * trailing-silence [Event.END_OF_SPEECH], the client minimum, or the owner's own stop
     * (manual stop / recorder 10-minute cap). Never cleared, so a real utterance is never
     * downgraded to a no-speech timeout by a later quiet stretch.
     */
    private var speechEstablished = false
    private var finished = false

    /**
     * True once a terminal event ([Event.END_OF_SPEECH] / [Event.NO_SPEECH_TIMEOUT]) was
     * emitted. The wall-clock stall watchdog ([awaitRecognitionCaptureStall]) consults
     * this to stand down once the session owner already has its terminal.
     */
    val terminalEmitted: Boolean
        get() = finished

    /** The session's absolute no-speech budget, exposed for the wall-clock stall watchdog. */
    val noSpeechBudgetMs: Long
        get() = noSpeechTimeoutMs

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

        // Amplitude fast-path: a frame above the threshold marks speech and restarts the
        // trailing-silence window. SPEECH_STARTED is reported only on the first such frame.
        // This is a heuristic — ambient noise can trip it — so on its own it never *prevents*
        // the absolute no-speech cap below from terminating a session: only a *sustained*
        // above-threshold run (real speech) does, by setting speechEstablished.
        val speaking = amplitude >= speechAmplitudeThreshold
        var event = Event.NONE
        if (speaking) {
            if (lastSpeechMs == null) {
                event = Event.SPEECH_STARTED
            }
            lastSpeechMs = nowMs
            val runStart = speechRunStartMs ?: nowMs.also { speechRunStartMs = it }
            if (nowMs - runStart >= minSustainedSpeechMs) {
                // An above-threshold run that has lasted minSustainedSpeechMs is real speech,
                // not an ambient flicker: lock the session into the speech path so a later
                // quiet stretch ends it on trailing silence, never as a no-speech timeout.
                speechEstablished = true
            }
        } else {
            // The run ended; the next above-threshold frame starts a fresh run that must
            // again last minSustainedSpeechMs to count — so isolated ambient flickers never
            // accumulate into an established speech session.
            speechRunStartMs = null
        }

        // The client's minimum-length extra (and the implicit floor) holds back every
        // terminal event, including the absolute cap, so a session can never be ended before
        // the caller's minimum utterance length.
        if (nowMs - startMs < minimumUtteranceMs) {
            return event
        }

        // Fast path: a detected utterance ended on its trailing silence.
        val speechMs = lastSpeechMs
        if (speechMs != null && nowMs - speechMs >= completeSilenceMs) {
            return terminal(Event.END_OF_SPEECH)
        }

        // Absolute backstop, independent of amplitude: no *sustained* speech was established
        // within the no-speech budget. For a genuinely silent session this is the only
        // terminal; for an ambient-noise session that kept tripping the amplitude heuristic
        // (brief flickers, no sustained run) this is what guarantees the session still ends.
        // The caller treats it as the SpeechRecognizer ERROR_SPEECH_TIMEOUT convention.
        //
        // Disabled once speech is established (a real utterance is in progress or has paused):
        // such a session ends on the trailing-silence END_OF_SPEECH, the client minimum, or
        // the owner's stop (manual / recorder 10-minute cap) — never cut short here. Because
        // the cap keys on speechEstablished, not on the current frame's amplitude, intermittent
        // ambient noise cannot hold it open the way a trailing-silence window can.
        if (!speechEstablished && nowMs - startMs >= noSpeechTimeoutMs) {
            return terminal(Event.NO_SPEECH_TIMEOUT)
        }

        return event
    }

    /**
     * A fresh endpointer with this session's configuration and the speech threshold scaled
     * for the session's microphone-gain multiplier (MIC-018). The recorder's amplitude
     * stream is POST-gain, so the absolute threshold tuned at gain 1.0 would classify
     * steady amplified ambient noise (fan/traffic at the user-settable 3-5x gain) as a
     * *sustained* speech run and permanently disable the no-speech cap; genuine speech
     * scales with the same gain, so establishment is unaffected. The base threshold is
     * never lowered — a sub-1.0 gain keeps the tuned operating point that protects
     * slow-quiet speakers. Returns a NEW endpointer (per-frame state is not carried over);
     * compensate before feeding any frames.
     */
    fun gainCompensated(gainMultiplier: Float): SpeechEndpointer =
        SpeechEndpointer(
            completeSilenceMs = completeSilenceMs,
            minimumUtteranceMs = minimumUtteranceMs,
            noSpeechTimeoutMs = noSpeechTimeoutMs,
            speechAmplitudeThreshold = speechAmplitudeThreshold * max(1f, gainMultiplier),
            minSustainedSpeechMs = minSustainedSpeechMs,
        )

    private fun terminal(event: Event): Event {
        finished = true
        return event
    }

    companion object {
        /** Default trailing silence that ends an utterance, matching platform engines (~2s). */
        const val DEFAULT_COMPLETE_SILENCE_MS = 2_000L

        /**
         * Default no-speech budget: the absolute cap within which an utterance must be
         * *completed* (trailing-silence END_OF_SPEECH) before the session terminates with
         * ERROR_SPEECH_TIMEOUT (~8-12s per platform convention; a 5s budget cut off slow
         * starters on-device). Independent of amplitude so ambient room noise cannot defeat
         * it (see the class doc).
         */
        const val DEFAULT_NO_SPEECH_TIMEOUT_MS = 10_000L

        /**
         * Mean-abs amplitude (at gain 1.0) above which a frame counts as speech. Quiet-room
         * noise sits around 0.001-0.005 on phone mics; voiced speech around 0.02-0.15.
         */
        const val DEFAULT_SPEECH_AMPLITUDE_THRESHOLD = 0.01f

        /**
         * Minimum duration a contiguous above-threshold run must last to be treated as a
         * genuine *speech session* rather than ambient flicker (which disables the no-speech
         * cap). At ~15 Hz frames this is a handful of consecutive voiced frames — short
         * enough that no real slow starter is ever misclassified as ambient noise, long
         * enough that the isolated, varying flickers of room noise (which dip below the
         * threshold between frames) never establish.
         */
        const val DEFAULT_MIN_SUSTAINED_SPEECH_MS = 300L

        /** Bounds applied to client-provided silence/minimum-length extras. */
        const val MIN_CLIENT_SILENCE_MS = 500L
        const val MAX_CLIENT_SILENCE_MS = 30_000L
        const val MAX_CLIENT_MINIMUM_LENGTH_MS = 60_000L

        /**
         * Floor for the stall watchdog's wall-clock budget (MIC-018): a session is never
         * declared stalled before max(noSpeechTimeoutMs, this) has elapsed, so the
         * watchdog can never undercut the endpointer's own no-speech terminal.
         */
        const val STALL_WATCHDOG_MIN_BUDGET_MS = 15_000L

        /** How long the recorder's sample count must sit unchanged to count as a frame stall. */
        const val STALL_WATCHDOG_STALL_MS = 5_000L

        /** Poll cadence of the stall watchdog (frames normally arrive at ~15 Hz). */
        const val STALL_WATCHDOG_POLL_MS = 500L
    }
}

/**
 * Builds the per-session [SpeechEndpointer] from the client's RecognizerIntent silence
 * extras, already extracted to milliseconds (null = extra not provided). Shared by both
 * system recognition surfaces ([ChirpRecognitionService] and [VoiceRecognitionActivity])
 * so they apply identical clamps and identical initial-silence semantics (IME-2):
 *
 *  - `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` sets the trailing-silence window,
 *    clamped to sane bounds;
 *  - `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS` delays any terminal event (the endpointer
 *    enforces it for the no-speech timeout too);
 *  - the no-speech budget never undercuts a client-requested complete-silence window, so
 *    a caller that tolerates 20s pauses is not cut off after 10s of leading silence.
 *
 * Pure (no android.content.Intent) so the policy stays unit-testable on the JVM.
 */
internal fun recognizerSessionEndpointer(
    clientCompleteSilenceMs: Long?,
    clientMinimumLengthMs: Long?,
): SpeechEndpointer {
    val completeSilenceMs =
        clientCompleteSilenceMs
            ?.coerceIn(SpeechEndpointer.MIN_CLIENT_SILENCE_MS, SpeechEndpointer.MAX_CLIENT_SILENCE_MS)
            ?: SpeechEndpointer.DEFAULT_COMPLETE_SILENCE_MS
    return SpeechEndpointer(
        completeSilenceMs = completeSilenceMs,
        minimumUtteranceMs =
            clientMinimumLengthMs?.coerceAtMost(SpeechEndpointer.MAX_CLIENT_MINIMUM_LENGTH_MS) ?: 0L,
        noSpeechTimeoutMs = maxOf(SpeechEndpointer.DEFAULT_NO_SPEECH_TIMEOUT_MS, completeSilenceMs),
    )
}

/**
 * Wall-clock frame-starvation watchdog for a recognition capture session (MIC-018). The
 * endpointer is purely event-driven — time only advances when amplitude frames arrive — so
 * a capture whose reads stop entirely (wedged Bluetooth route, HAL stall: a blocking read
 * that never returns) feeds it nothing, neither terminal can ever fire, and the session
 * would listen forever with a frozen waveform. This companion suspends until one outcome
 * is known:
 *
 *  - returns true when the watchdog budget (max of the session's no-speech budget and
 *    [SpeechEndpointer.STALL_WATCHDOG_MIN_BUDGET_MS]) has elapsed with no endpointer
 *    terminal AND [sampleCount] has not advanced for at least
 *    [SpeechEndpointer.STALL_WATCHDOG_STALL_MS] — the caller routes into its existing
 *    generation-gated no-speech path, which keeps a racing stop idempotent;
 *  - returns false when the endpointer emitted its own terminal: frames are flowing and
 *    the session owner already acted.
 *
 * Callers launch this in a session-scoped job and cancel it on any terminal/stop; a stale
 * firing is harmless either way because the no-speech paths are generation-gated.
 */
internal suspend fun awaitRecognitionCaptureStall(
    endpointer: SpeechEndpointer,
    sampleCount: () -> Long,
): Boolean {
    val budgetMs = maxOf(endpointer.noSpeechBudgetMs, SpeechEndpointer.STALL_WATCHDOG_MIN_BUDGET_MS)
    // The stall window is measured inside the budget, so the earliest possible firing
    // lands exactly at the budget (budget minus the stall window of lead time, then at
    // least a full stall window of unchanged sample count).
    delay(budgetMs - SpeechEndpointer.STALL_WATCHDOG_STALL_MS)
    var lastSampleCount = sampleCount()
    var unchangedMs = 0L
    while (true) {
        if (endpointer.terminalEmitted) {
            return false
        }
        delay(SpeechEndpointer.STALL_WATCHDOG_POLL_MS)
        val count = sampleCount()
        if (count == lastSampleCount) {
            unchangedMs += SpeechEndpointer.STALL_WATCHDOG_POLL_MS
            if (unchangedMs >= SpeechEndpointer.STALL_WATCHDOG_STALL_MS) {
                return true
            }
        } else {
            lastSampleCount = count
            unchangedMs = 0L
        }
    }
}
