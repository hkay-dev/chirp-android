package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import androidx.annotation.StringRes
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.service.RecordingAutoStopEvent
import dev.chirpboard.app.feature.recording.service.RecordingAutoStopReason

/**
 * ERR-13/ERR-14: in-app twin of RecordingNotificationFactory.notifyAutoStopped's reason
 * mapping, shared by the Home and Record screen snackbars so the wording stays identical
 * to the system notification.
 */
@StringRes
internal fun RecordingAutoStopReason.reasonStringRes(): Int =
    when (this) {
        RecordingAutoStopReason.STORAGE_CRITICAL -> R.string.rec_auto_stop_storage
        RecordingAutoStopReason.FOCUS_LOST -> R.string.rec_auto_stop_focus
        RecordingAutoStopReason.INPUT_DEVICE_LOST -> R.string.rec_auto_stop_device
        RecordingAutoStopReason.CAPTURE_ERROR -> R.string.rec_auto_stop_capture_error
    }

/** Full snackbar message: "Recording stopped and saved — <reason>". */
internal fun RecordingAutoStopReason.autoStopSnackbarMessage(context: Context): String =
    context.getString(R.string.rec_auto_stop_snackbar, context.getString(reasonStringRes()))

/**
 * Event-level snackbar message: like [autoStopSnackbarMessage] but a device-lost stop
 * NAMES the lost device ("Buds disconnected") when the event carries it.
 */
internal fun RecordingAutoStopEvent.autoStopSnackbarMessage(context: Context): String {
    val reasonText =
        if (reason == RecordingAutoStopReason.INPUT_DEVICE_LOST && !detail.isNullOrBlank()) {
            context.getString(R.string.rec_auto_stop_device_named, detail)
        } else {
            context.getString(reason.reasonStringRes())
        }
    return context.getString(R.string.rec_auto_stop_snackbar, reasonText)
}
