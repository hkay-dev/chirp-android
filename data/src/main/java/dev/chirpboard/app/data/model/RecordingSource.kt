package dev.chirpboard.app.data.model

/**
 * Source of a recording.
 */
import androidx.annotation.Keep

@Keep
enum class RecordingSource {
    /** Created from app FAB or profile shortcut */
    APP,

    /** Created during IME usage */
    KEYBOARD,

    /** Created from home screen widget */
    WIDGET,

    /** Imported from device storage */
    IMPORTED
}
