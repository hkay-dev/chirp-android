package dev.parakeeboard.app.core.transcription

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
     * Transcribe audio samples to text.
     * @param samples PCM audio samples as FloatArray, normalized to [-1.0, 1.0]
     * @param sampleRate Sample rate of the audio (typically 16000 Hz)
     * @return Transcribed text, or empty string if transcription failed
     */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String
}
