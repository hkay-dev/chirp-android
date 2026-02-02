package dev.parakeeboard.app.data.model

/**
 * Status of a recording in the processing pipeline.
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
    FAILED
}
