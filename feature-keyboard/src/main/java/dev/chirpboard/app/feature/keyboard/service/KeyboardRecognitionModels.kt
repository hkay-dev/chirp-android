package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.core.transcription.TranscriptionOutcome

internal sealed interface KeyboardTranscriptionResolution {
    data class Success(val text: String) : KeyboardTranscriptionResolution
    object NoSpeech : KeyboardTranscriptionResolution
    data class Failure(val message: String) : KeyboardTranscriptionResolution
}

internal fun mapKeyboardTranscriptionOutcome(
    outcome: TranscriptionOutcome
): KeyboardTranscriptionResolution {
    return when (outcome) {
        is TranscriptionOutcome.Success -> {
            if (outcome.text.isBlank()) {
                KeyboardTranscriptionResolution.NoSpeech
            } else {
                KeyboardTranscriptionResolution.Success(outcome.text)
            }
        }
        TranscriptionOutcome.NoSpeech -> KeyboardTranscriptionResolution.NoSpeech
        is TranscriptionOutcome.ModelUnavailable -> {
            KeyboardTranscriptionResolution.Failure("Recognizer unavailable: ${outcome.reason}")
        }
        is TranscriptionOutcome.EngineError -> {
            KeyboardTranscriptionResolution.Failure(
                "Transcription engine failed: ${outcome.reason}"
            )
        }
    }
}

/**
 * Interface for the speech recognizer.
 * This allows the keyboard module to be decoupled from the specific recognizer implementation.
 */
interface RecognizerProvider {
    fun isReady(): Boolean
    fun isModelDownloaded(): Boolean
    suspend fun initialize(): Boolean
    suspend fun transcribe(samples: FloatArray): TranscriptionOutcome
}
