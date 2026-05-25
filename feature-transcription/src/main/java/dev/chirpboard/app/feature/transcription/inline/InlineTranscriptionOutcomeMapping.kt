package dev.chirpboard.app.feature.transcription.inline

import dev.chirpboard.app.core.transcription.TranscriptionOutcome

internal sealed interface InlineTranscriptionResolution {
    data class Success(val text: String) : InlineTranscriptionResolution

    data object NoSpeech : InlineTranscriptionResolution

    data class Failure(val message: String) : InlineTranscriptionResolution
}

internal fun mapInlineTranscriptionOutcome(outcome: TranscriptionOutcome): InlineTranscriptionResolution =
    when (outcome) {
        is TranscriptionOutcome.Success -> {
            if (outcome.text.isBlank()) {
                InlineTranscriptionResolution.NoSpeech
            } else {
                InlineTranscriptionResolution.Success(outcome.text)
            }
        }

        TranscriptionOutcome.NoSpeech -> InlineTranscriptionResolution.NoSpeech

        is TranscriptionOutcome.ModelUnavailable -> {
            InlineTranscriptionResolution.Failure("Recognizer unavailable: ${outcome.reason}")
        }

        is TranscriptionOutcome.EngineError -> {
            InlineTranscriptionResolution.Failure("Transcription engine failed: ${outcome.reason}")
        }
    }
