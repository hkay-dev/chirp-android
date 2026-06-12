package dev.chirpboard.app.core.recording

/**
 * Persistent source value for a saved recording.
 */
enum class RecordingSource {
    /** Created from app FAB or profile shortcut */
    APP,

    /** Created during IME usage */
    KEYBOARD,

    /** Created from home screen widget */
    WIDGET,

    /** Imported from device storage */
    IMPORTED,
}

/**
 * Persistent status value for the recording processing pipeline.
 */
enum class RecordingStatus {
    /** Audio capture in progress */
    RECORDING,

    /** Waiting in transcription queue */
    PENDING_TRANSCRIPTION,

    /** Currently being transcribed */
    TRANSCRIBING,

    /** Transcribed, awaiting LLM processing */
    PENDING_ENHANCEMENT,

    /** LLM processing in progress */
    ENHANCING,

    /** Fully processed */
    COMPLETED,

    /** Error occurred */
    FAILED,

    /**
     * Saved but deliberately not queued: the recording's profile has Auto Transcribe
     * disabled, or the user cancelled queued/running transcription. Excluded from
     * automatic queue recovery; transcription starts only from an explicit user
     * action (the re-transcribe affordance).
     */
    AWAITING_MANUAL_TRANSCRIPTION,
}
