package dev.chirpboard.app.feature.recording.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Why the recording service ended a session on its own (never user-initiated stops). */
enum class RecordingAutoStopReason {
    STORAGE_CRITICAL,
    FOCUS_LOST,
    INPUT_DEVICE_LOST,
    CAPTURE_ERROR,
}

/** Why the recording service paused a session on its own. */
enum class RecordingAutoPauseReason {
    FOCUS_LOST_TRANSIENT,
}

/** A single auto-stop occurrence; [atEpochMs] disambiguates repeats of the same reason. */
data class RecordingAutoStopEvent(
    val reason: RecordingAutoStopReason,
    val atEpochMs: Long = System.currentTimeMillis(),
)

/**
 * User-facing recording service signals that outlive the notification: why a recording
 * auto-stopped or auto-paused, whether the mic is delivering pure silence, and whether
 * storage is running low. The recording/home screens render these (snackbar/banner);
 * [clearAutoStopEvent] acknowledges a consumed auto-stop.
 *
 * The service is the only writer. State here is advisory UI state and must never feed
 * back into stop/finalize decisions.
 */
@Singleton
class RecordingServiceEvents
    @Inject
    constructor() {
        private val _autoStopEvent = MutableStateFlow<RecordingAutoStopEvent?>(null)
        val autoStopEvent: StateFlow<RecordingAutoStopEvent?> = _autoStopEvent.asStateFlow()

        private val _autoPauseReason = MutableStateFlow<RecordingAutoPauseReason?>(null)
        val autoPauseReason: StateFlow<RecordingAutoPauseReason?> = _autoPauseReason.asStateFlow()

        private val _silenceDetected = MutableStateFlow(false)
        val silenceDetected: StateFlow<Boolean> = _silenceDetected.asStateFlow()

        private val _storageLow = MutableStateFlow(false)
        val storageLow: StateFlow<Boolean> = _storageLow.asStateFlow()

        fun publishAutoStop(reason: RecordingAutoStopReason) {
            _autoStopEvent.value = RecordingAutoStopEvent(reason)
        }

        fun clearAutoStopEvent() {
            _autoStopEvent.value = null
        }

        fun setAutoPauseReason(reason: RecordingAutoPauseReason?) {
            _autoPauseReason.value = reason
        }

        fun setSilenceDetected(silenced: Boolean) {
            _silenceDetected.value = silenced
        }

        fun setStorageLow(storageLow: Boolean) {
            _storageLow.value = storageLow
        }

        /** Clears per-session transient state when a session ends; auto-stop events persist. */
        fun resetSessionState() {
            _autoPauseReason.value = null
            _silenceDetected.value = false
            _storageLow.value = false
        }
    }
