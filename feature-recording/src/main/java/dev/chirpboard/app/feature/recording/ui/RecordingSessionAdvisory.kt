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

    /** The mic is delivering pure silence — another app owns it or the privacy toggle is off. */
    SILENCED,

    /** Storage is low; the recording will be auto-stopped (and saved) if it hits critical. */
    STORAGE_LOW,
}

/**
 * Resolves the single advisory to show, with the same priority order as the notification
 * status line: the focus pause explains a visibly-paused session (most urgent), silence
 * means nothing is being captured, low storage is a forewarning.
 */
internal fun resolveSessionAdvisory(
    autoPauseReason: RecordingAutoPauseReason?,
    silenceDetected: Boolean,
    storageLow: Boolean,
): RecordingSessionAdvisory? =
    when {
        autoPauseReason == RecordingAutoPauseReason.FOCUS_LOST_TRANSIENT ->
            RecordingSessionAdvisory.PAUSED_BY_FOCUS_LOSS
        silenceDetected -> RecordingSessionAdvisory.SILENCED
        storageLow -> RecordingSessionAdvisory.STORAGE_LOW
        else -> null
    }

/** Reuses the notification strings verbatim so both surfaces always say the same thing. */
@StringRes
internal fun RecordingSessionAdvisory.advisoryStringRes(): Int =
    when (this) {
        RecordingSessionAdvisory.PAUSED_BY_FOCUS_LOSS -> R.string.rec_notification_paused_focus
        RecordingSessionAdvisory.SILENCED -> R.string.rec_notification_silence
        RecordingSessionAdvisory.STORAGE_LOW -> R.string.rec_notification_storage_low
    }

/**
 * Like [advisoryStringRes] but the silence hint NAMES the active input device and
 * suggests switching ("No audio from Buds — … Try a different microphone."), matching
 * the live notification's named status line. Pass the device name from
 * [dev.chirpboard.app.core.audio.AudioInputDeviceSelector.activeDeviceLabel].
 */
internal fun RecordingSessionAdvisory.advisoryText(
    context: Context,
    activeDeviceName: String?,
): String =
    if (this == RecordingSessionAdvisory.SILENCED && !activeDeviceName.isNullOrBlank()) {
        context.getString(R.string.rec_notification_silence_named, activeDeviceName)
    } else {
        context.getString(advisoryStringRes())
    }
