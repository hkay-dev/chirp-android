package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import androidx.annotation.StringRes
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.service.RecordingAutoPauseReason

/**
 * AUD-02/AUD-05/ERR-14: in-app twin of the live notification's transient status line
 * (RecordingService.currentRecordingStatusText). The service publishes these conditions on
 * [dev.chirpboard.app.feature.recording.service.RecordingServiceEvents]; the Record screen
 * banner and the Home live row render them so the explanation is visible without pulling
 * down the notification shade. Advisory/display-only — never feeds stop/save decisions.
 */
enum class RecordingSessionAdvisory {
    /** Paused by a transient audio-focus loss (call/alarm/assistant); will auto-resume. */
    PAUSED_BY_FOCUS_LOSS,

    /** Resume re-resolved the input device and landed on a different microphone. */
    DEVICE_CHANGED_ON_RESUME,

    /** The mic is delivering pure silence — another app owns it or the privacy toggle is off. */
    SILENCED,

    /** Storage is low; the recording will be auto-stopped (and saved) if it hits critical. */
    STORAGE_LOW,
}

/**
 * Resolves the single advisory to show, with the same priority order as the notification
 * status line: the focus pause explains a visibly-paused session (most urgent), a device
 * change means the session continues on a different microphone, silence means nothing is
 * being captured, low storage is a forewarning.
 */
internal fun resolveSessionAdvisory(
    autoPauseReason: RecordingAutoPauseReason?,
    silenceDetected: Boolean,
    storageLow: Boolean,
    deviceChangedOnResume: Boolean = false,
): RecordingSessionAdvisory? =
    when {
        autoPauseReason == RecordingAutoPauseReason.FOCUS_LOST_TRANSIENT ->
            RecordingSessionAdvisory.PAUSED_BY_FOCUS_LOSS
        deviceChangedOnResume -> RecordingSessionAdvisory.DEVICE_CHANGED_ON_RESUME
        silenceDetected -> RecordingSessionAdvisory.SILENCED
        storageLow -> RecordingSessionAdvisory.STORAGE_LOW
        else -> null
    }

/** Reuses the notification strings verbatim so both surfaces always say the same thing. */
@StringRes
internal fun RecordingSessionAdvisory.advisoryStringRes(): Int =
    when (this) {
        RecordingSessionAdvisory.PAUSED_BY_FOCUS_LOSS -> R.string.rec_notification_paused_focus
        RecordingSessionAdvisory.DEVICE_CHANGED_ON_RESUME -> R.string.rec_notification_device_changed
        RecordingSessionAdvisory.SILENCED -> R.string.rec_notification_silence
        RecordingSessionAdvisory.STORAGE_LOW -> R.string.rec_notification_storage_low
    }

/**
 * Like [advisoryStringRes] but the silence and device-change hints NAME the active input
 * device ("No audio from Buds — … Try a different microphone." / "Microphone changed —
 * now recording from Buds"), matching the live notification's named status line. Pass the
 * device name from [dev.chirpboard.app.core.audio.AudioInputDeviceSelector.activeDeviceLabel].
 */
internal fun RecordingSessionAdvisory.advisoryText(
    context: Context,
    activeDeviceName: String?,
): String =
    when {
        this == RecordingSessionAdvisory.SILENCED && !activeDeviceName.isNullOrBlank() ->
            context.getString(R.string.rec_notification_silence_named, activeDeviceName)
        this == RecordingSessionAdvisory.DEVICE_CHANGED_ON_RESUME && !activeDeviceName.isNullOrBlank() ->
            context.getString(R.string.rec_notification_device_changed_named, activeDeviceName)
        else -> context.getString(advisoryStringRes())
    }
