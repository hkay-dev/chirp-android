package dev.chirpboard.app.core.recording

import java.util.UUID

/**
 * The source of a recording attempt.
 */
enum class RecordingOrigin {
    /** Recording started from the main app */
    APP,
    /** Recording started from the keyboard IME */
    KEYBOARD,
    /** Recording started from the home screen widget */
    WIDGET,
    /** Recording started from a system speech-recognition surface (RECOGNIZE_SPEECH dialog/service) */
    RECOGNITION
}

/**
 * Represents the current recording state.
 */
sealed class RecordingState {
    /** No recording in progress */
    object Idle : RecordingState()
    
    /** Recording is starting up */
    data class Starting(
        val origin: RecordingOrigin,
        val profileId: UUID? = null,
        val recordingId: UUID? = null,
    ) : RecordingState()
    
    /** Recording is in progress */
    data class Recording(
        val origin: RecordingOrigin,
        val profileId: UUID? = null,
        val startTimeMs: Long = System.currentTimeMillis(),
        val audioFilePath: String? = null,
        val recordingId: UUID? = null,
        /**
         * Monotonic stamp (`System.nanoTime()` in milliseconds) for when this segment
         * started. Elapsed arithmetic must use this and never [startTimeMs]: an NTP/RTC
         * correction or a manual clock change mid-recording moves the wall clock and would
         * skew — or negate — the stored duration and the live timer. [startTimeMs] stays a
         * real calendar timestamp for anything that displays or records when a session began.
         *
         * The state is in-memory only (never persisted across process death), so a monotonic
         * base is safe here; it is nanoTime rather than SystemClock.elapsedRealtime so the
         * runtime stays unit-testable on a plain JVM without Robolectric.
         */
        val startMonotonicMs: Long = System.nanoTime() / 1_000_000,
        /**
         * Milliseconds recorded before this segment started (earlier segments across
         * pauses and file rotations). Total elapsed = this + (now - [startMonotonicMs]).
         * Carried in the state so timers can render the true total even when they
         * (re)compose mid-session and never saw the earlier Paused states.
         */
        val accumulatedBeforeSegmentMs: Long = 0L,
    ) : RecordingState()
    
    /** Recording is paused */
    data class Paused(
        val origin: RecordingOrigin,
        val profileId: UUID? = null,
        val audioFilePath: String? = null,
        /** Total milliseconds recorded before this pause (sum of all active segments) */
        val accumulatedMs: Long = 0L,
        val recordingId: UUID? = null,
    ) : RecordingState()
    
    /** Recording is stopping */
    data class Stopping(
        val origin: RecordingOrigin,
        val profileId: UUID? = null,
        val audioFilePath: String? = null,
        val recordingId: UUID? = null,
    ) : RecordingState()
    
    /** Recording failed */
    data class Error(
        val origin: RecordingOrigin,
        val message: String,
        val cause: Throwable? = null
    ) : RecordingState()
    
    /** Check if recording is active (starting, recording, paused, or stopping) */
    val isActive: Boolean
        get() = this is Starting || this is Recording || this is Paused || this is Stopping
    
    /** Get the current recording origin, or null if idle/error */
    val activeOrigin: RecordingOrigin?
        get() = when (this) {
            is Starting -> origin
            is Recording -> origin
            is Paused -> origin
            is Stopping -> origin
            else -> null
        }

    /** Database recording ID for the active session, when one has been created. */
    val activeRecordingId: UUID?
        get() = when (this) {
            is Starting -> recordingId
            is Recording -> recordingId
            is Paused -> recordingId
            is Stopping -> recordingId
            else -> null
        }
}
