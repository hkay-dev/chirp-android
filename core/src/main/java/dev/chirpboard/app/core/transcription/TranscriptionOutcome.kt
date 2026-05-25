package dev.chirpboard.app.core.transcription

/**
 * Trusted recognizer timing aligned to a single transcript word.
 */
data class RecognizedWordTiming(
    val text: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
 )

/**
 * Typed outcome for transcription attempts.
 */
sealed interface TranscriptionOutcome {
    data class Success(
        val text: String,
        val wordTimings: List<RecognizedWordTiming>? = null,
    ) : TranscriptionOutcome
    object NoSpeech : TranscriptionOutcome
    data class ModelUnavailable(val reason: String) : TranscriptionOutcome
    data class EngineError(
        val reason: String,
        val retryable: Boolean = false
    ) : TranscriptionOutcome
}
