package dev.chirpboard.app.core.transcription

/**
 * Phases emitted while inline dictation transcription runs.
 */
sealed interface InlineTranscriptionPhase {
    data object Idle : InlineTranscriptionPhase

    data class LoadingModel(val progress: Float? = null) : InlineTranscriptionPhase

    data object Transcribing : InlineTranscriptionPhase

    data object Polishing : InlineTranscriptionPhase

    data class Error(val message: String) : InlineTranscriptionPhase

    data class LlmError(val message: String) : InlineTranscriptionPhase
}

data class InlineTranscriptionRequest(
    val samples: FloatArray,
    val llmEnabled: Boolean,
    val processingModeId: String,
    val correlationPrefix: String = "keyboard",
    val audioSource: InlineAudioSource = InlineAudioSource.InMemory(samples),
)

sealed interface InlineAudioSource {
    val sampleRate: Int

    data class InMemory(
        val samples: FloatArray,
        override val sampleRate: Int = 16000,
    ) : InlineAudioSource

    data class PcmFloatFile(
        val path: String,
        val sampleCount: Long,
        override val sampleRate: Int = 16000,
    ) : InlineAudioSource
}

interface InlineCapturePersistence {
    fun prepareAudioSource(audioSource: InlineAudioSource) = Unit

    suspend fun persist(
        samples: FloatArray?,
        rawText: String?,
        processedText: String?,
        errorMessage: String? = null,
    )

    suspend fun persistAudioSource(
        audioSource: InlineAudioSource?,
        rawText: String?,
        processedText: String?,
        errorMessage: String? = null,
    ) {
        persist(
            samples = (audioSource as? InlineAudioSource.InMemory)?.samples,
            rawText = rawText,
            processedText = processedText,
            errorMessage = errorMessage,
        )
    }

    fun discardSamples()
}

/**
 * Shared inline STT + optional LLM path for IME and voice dialog surfaces.
 */
interface InlineTranscriptionPort {
    val phase: kotlinx.coroutines.flow.StateFlow<InlineTranscriptionPhase>

    fun resetPhase()

    fun setError(message: String)

    suspend fun transcribe(
        request: InlineTranscriptionRequest,
        persistence: InlineCapturePersistence? = null,
        commitText: (String) -> Unit,
        onRecordingCompleted: () -> Unit = {},
        onRecordingError: (String) -> Unit = {},
    )
}

interface InlineTranscriptionCoordinator : InlineTranscriptionPort
