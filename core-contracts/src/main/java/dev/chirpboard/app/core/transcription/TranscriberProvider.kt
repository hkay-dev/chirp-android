package dev.chirpboard.app.core.transcription

/**
 * Interface for transcribing audio samples to text.
 * 
 * This abstraction allows the transcription feature module to be decoupled
 * from the specific recognizer implementation (Sherpa-ONNX in the app module).
 */
interface TranscriberProvider {
    /**
     * Whether the transcriber is ready to transcribe (model loaded).
     */
    fun isReady(): Boolean
    
    /**
     * Whether the model is downloaded on the device.
     */
    fun isModelDownloaded(): Boolean
    
    /**
     * Initialize the transcriber (load model).
     * @return true if initialization was successful
     */
    suspend fun initialize(): Boolean
    
    /**
     * Transcribe audio samples to a typed outcome.
     * @param samples PCM audio samples as FloatArray, normalized to [-1.0, 1.0]
     * @param sampleRate Sample rate of the audio (typically 16000 Hz)
     */
    suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int = 16000
    ): TranscriptionOutcome
    /**
     * Release the model from memory.
     */
    suspend fun release()

}

/**
 * Optional low-latency first pass used only for visible partial text. Implementations must own
 * separate model and synchronization state from [TranscriberProvider] so preview work can never
 * queue the authoritative final decode.
 */
interface StreamingTranscriberProvider {
    /** Prepares model files and native state. This must never open the microphone. */
    suspend fun prepare(): Boolean

    /** Opens a fresh incremental stream, or returns null when the optional preview is unavailable. */
    suspend fun openSession(sampleRate: Int = 16000): StreamingTranscriptionSession?

    /** Requests native preview-model cleanup once any open stream has closed. */
    suspend fun release() = Unit
}

interface StreamingTranscriptionSession {
    /** Accepts only samples not previously supplied to this session and returns the latest text. */
    suspend fun accept(samples: FloatArray): String

    /** Flushes the stream and returns its final best-effort preview text. */
    suspend fun finish(): String

    suspend fun close()
}
