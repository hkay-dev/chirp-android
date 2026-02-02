package dev.parakeeboard.app.core.recording

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
    WIDGET
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
        val profileId: UUID? = null
    ) : RecordingState()
    
    /** Recording is in progress */
    data class Recording(
        val origin: RecordingOrigin,
        val profileId: UUID? = null,
        val startTimeMs: Long = System.currentTimeMillis(),
        val audioFilePath: String? = null
    ) : RecordingState()
    
    /** Recording is stopping */
    data class Stopping(
        val origin: RecordingOrigin,
        val profileId: UUID? = null
    ) : RecordingState()
    
    /** Recording failed */
    data class Error(
        val origin: RecordingOrigin,
        val message: String,
        val cause: Throwable? = null
    ) : RecordingState()
    
    /** Check if recording is active (starting, recording, or stopping) */
    val isActive: Boolean
        get() = this is Starting || this is Recording || this is Stopping
    
    /** Get the current recording origin, or null if idle/error */
    val activeOrigin: RecordingOrigin?
        get() = when (this) {
            is Starting -> origin
            is Recording -> origin
            is Stopping -> origin
            else -> null
        }
}
