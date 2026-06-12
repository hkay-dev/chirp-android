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

/**
 * A resume re-resolved the input device and landed on a different microphone than the one
 * in use before the pause. Re-resolution is deliberate (it is what makes the supported
 * pause -> swap -> resume flow work), but the swap must be visible — the session continues
 * with a different gain structure and noise floor. Display-only; never feeds selection.
 */
data class RecordingDeviceChange(
    /** Name of the device the session captured from before the pause. */
    val fromDeviceName: String?,
    /** Name of the device the resume actually selected. */
    val toDeviceName: String?,
)

/** A single auto-stop occurrence; [atEpochMs] disambiguates repeats of the same reason. */
data class RecordingAutoStopEvent(
    val reason: RecordingAutoStopReason,
    /** Optional human detail for the reason — e.g. the NAME of the lost input device. */
    val detail: String? = null,
    val atEpochMs: Long = System.currentTimeMillis(),
) {
    /**
     * Display-time staleness gate: an unconsumed event survives process death only in this
     * singleton's lifetime, but it also survives long backgrounding — without this check a
     * days-old "stopped and saved" snackbar could greet the next app open. Stale events are
     * acknowledged silently by the screens instead of being shown; fresh events keep the
     * deliberate show-then-consume behavior (so an interrupted snackbar re-surfaces).
     */
    fun isStale(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs - atEpochMs > MAX_DISPLAY_AGE_MS

    companion object {
        /** Events older than this are silently consumed at display time, never shown. */
        const val MAX_DISPLAY_AGE_MS: Long = 5 * 60_000L
    }
}

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

        private val _deviceChangedOnResume = MutableStateFlow<RecordingDeviceChange?>(null)
        val deviceChangedOnResume: StateFlow<RecordingDeviceChange?> = _deviceChangedOnResume.asStateFlow()

        fun publishAutoStop(
            reason: RecordingAutoStopReason,
            detail: String? = null,
        ) {
            _autoStopEvent.value = RecordingAutoStopEvent(reason, detail)
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

        fun setDeviceChangedOnResume(change: RecordingDeviceChange?) {
            _deviceChangedOnResume.value = change
        }

        /** Clears per-session transient state when a session ends; auto-stop events persist. */
        fun resetSessionState() {
            _autoPauseReason.value = null
            _silenceDetected.value = false
            _storageLow.value = false
            _deviceChangedOnResume.value = null
        }
    }
