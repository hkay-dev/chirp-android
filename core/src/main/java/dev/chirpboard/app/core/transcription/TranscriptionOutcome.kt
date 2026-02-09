package dev.chirpboard.app.core.transcription

/**
 * Typed outcome for transcription attempts.
 */
sealed interface TranscriptionOutcome {
    data class Success(val text: String) : TranscriptionOutcome
    object NoSpeech : TranscriptionOutcome
    data class ModelUnavailable(val reason: String) : TranscriptionOutcome
    data class EngineError(
        val reason: String,
        val retryable: Boolean = false
    ) : TranscriptionOutcome
}
