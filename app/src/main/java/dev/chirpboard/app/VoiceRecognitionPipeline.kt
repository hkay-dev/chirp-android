package dev.chirpboard.app

import android.speech.SpeechRecognizer
import android.util.Log
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.llm.ProcessingMode
import dev.chirpboard.app.llm.TextProcessor

internal class VoiceRecognitionPipeline(
    private val tag: String,
    private val recognizerProvider: () -> SherpaRecognizer?,
    private val textProcessor: TextProcessor,
) {
    suspend fun process(
        samples: FloatArray,
        llmEnabled: Boolean,
        processingMode: ProcessingMode,
        onPartialTranscript: (String) -> Unit,
    ): VoiceRecognitionPipelineResult {
        if (samples.isEmpty()) {
            Log.w(tag, "No audio samples")
            return VoiceRecognitionPipelineResult.Error(SpeechRecognizer.ERROR_NO_MATCH)
        }

        val recognizer = recognizerProvider()
        if (recognizer == null) {
            Log.e(tag, "Recognizer is null")
            return VoiceRecognitionPipelineResult.Error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        }

        Log.d(tag, "Checking if recognizer is ready: ${recognizer.isReady}")
        if (!recognizer.isReady) {
            Log.w(tag, "Recognizer not ready")
            return VoiceRecognitionPipelineResult.Error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        }

        Log.d(tag, "Starting transcription...")
        var text = when (val outcome = recognizer.transcribeOutcome(samples)) {
            is TranscriptionOutcome.Success -> outcome.text
            TranscriptionOutcome.NoSpeech -> {
                Log.w(tag, "No speech detected")
                return VoiceRecognitionPipelineResult.Error(SpeechRecognizer.ERROR_NO_MATCH)
            }

            is TranscriptionOutcome.ModelUnavailable -> {
                Log.w(tag, "Model unavailable: ${outcome.reason}")
                return VoiceRecognitionPipelineResult.Error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            }

            is TranscriptionOutcome.EngineError -> {
                Log.e(tag, "Engine error: ${outcome.reason}")
                return VoiceRecognitionPipelineResult.Error(SpeechRecognizer.ERROR_CLIENT)
            }
        }

        Log.d(tag, "Raw transcription: '$text' (length: ${text.length})")
        onPartialTranscript(text)

        if (text.isBlank()) {
            Log.w(tag, "Empty transcription result")
            return VoiceRecognitionPipelineResult.Error(SpeechRecognizer.ERROR_NO_MATCH)
        }

        if (llmEnabled) {
            Log.d(tag, "Applying LLM processing with mode: ${processingMode.id}")
            val result = textProcessor.process(text, processingMode)
            result.onSuccess { processedText ->
                text = processedText
                Log.d(tag, "Processed text: '$text'")
            }.onFailure { error ->
                Log.w(tag, "LLM processing failed: ${error.message}, using raw text")
            }
        }

        return VoiceRecognitionPipelineResult.Success("$text ")
    }
}

internal sealed interface VoiceRecognitionPipelineResult {
    data class Success(val text: String) : VoiceRecognitionPipelineResult

    data class Error(val code: Int) : VoiceRecognitionPipelineResult
}
