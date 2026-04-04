package dev.chirpboard.app.data.model

/**
 * Source of a recording.
 */
enum class RecordingSource {
    /** Created from app FAB or profile shortcut */
    APP,

    /** Created during IME usage */
    KEYBOARD,

    /** Created from home screen widget */
    WIDGET,
}
