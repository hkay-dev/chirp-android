package dev.chirpboard.app.feature.keyboard.recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Errors that can occur during audio recording.
 */
sealed class RecordingError(val userMessage: String) {
    object InvalidOperation : RecordingError("Microphone not ready")
    object BadValue : RecordingError("Recording configuration error")
    object DeadObject : RecordingError("Microphone disconnected")
    data class Generic(val code: Int) : RecordingError("Recording failed (code: $code)")
    object TooShort : RecordingError("Recording too short")
}

/**
 * Handles audio recording for keyboard voice input.
 * Records PCM float samples at 16kHz mono for speech recognition.
 */
class VoiceRecorder {
    companion object {
        private const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 16000
        const val MINIMUM_RECORDING_MS = 300L
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val samples = mutableListOf<Float>()
    
    // Synchronization for race condition between start() and collectSamples()
    private var recordingReady = CompletableDeferred<Unit>()
    private var recordingStartTimeMs: Long = 0
    
    /** Microphone gain multiplier (1.0 = no boost, 2.0 = double volume, etc.) */
    var gainMultiplier: Float = 1.0f
    
    /** Callback invoked when a recording error occurs */
    var onRecordingError: ((RecordingError) -> Unit)? = null
    
    /** Whether an error occurred during the current recording session */
    private var hasError = false

    // Amplitude tracking for waveform visualization
    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()
    private val amplitudeHistory = mutableListOf<Float>()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording.get()) return false
        
        // Reset synchronization
        recordingReady = CompletableDeferred()
        hasError = false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            recordingReady.completeExceptionally(IllegalStateException("Invalid buffer size"))
            return false
        }

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                recordingReady.completeExceptionally(IllegalStateException("AudioRecord not initialized"))
                return false
            }

            synchronized(samples) {
                samples.clear()
            }
            amplitudeHistory.clear()
            _amplitudes.value = emptyList()
            audioRecord?.startRecording()
            isRecording.set(true)
            recordingStartTimeMs = System.currentTimeMillis()
            
            // Signal that recording is ready for collection
            recordingReady.complete(Unit)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            recordingReady.completeExceptionally(e)
            false
        }
    }

    suspend fun collectSamples() = withContext(Dispatchers.IO) {
        // Wait for start() to complete before collecting
        try {
            recordingReady.await()
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed to start", e)
            return@withContext
        }
        
        val record = audioRecord ?: return@withContext
        val buffer = FloatArray(1024)
        hasError = false

        while (isActive && isRecording.get()) {
            val readResult = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            
            // Check for errors
            when {
                readResult == AudioRecord.ERROR_INVALID_OPERATION -> {
                    hasError = true
                    onRecordingError?.invoke(RecordingError.InvalidOperation)
                    isRecording.set(false)
                    return@withContext
                }
                readResult == AudioRecord.ERROR_BAD_VALUE -> {
                    hasError = true
                    onRecordingError?.invoke(RecordingError.BadValue)
                    isRecording.set(false)
                    return@withContext
                }
                readResult == AudioRecord.ERROR_DEAD_OBJECT -> {
                    hasError = true
                    onRecordingError?.invoke(RecordingError.DeadObject)
                    isRecording.set(false)
                    return@withContext
                }
                readResult < 0 -> {
                    hasError = true
                    onRecordingError?.invoke(RecordingError.Generic(readResult))
                    isRecording.set(false)
                    return@withContext
                }
                readResult > 0 -> {
                    // Normal case - process samples
                    synchronized(samples) {
                        for (i in 0 until readResult) {
                            // Apply gain boost and clamp to prevent distortion
                            val boostedSample = (buffer[i] * gainMultiplier).coerceIn(-1f, 1f)
                            samples.add(boostedSample)
                        }
                    }
                    // Calculate amplitude for visualization (RMS of buffer)
                    var sum = 0f
                    for (i in 0 until readResult) {
                        sum += abs(buffer[i] * gainMultiplier)
                    }
                    val amplitude = (sum / readResult).coerceIn(0f, 1f)
                    synchronized(amplitudeHistory) {
                        amplitudeHistory.add(amplitude)
                        // Keep last 5 samples for waveform display
                        while (amplitudeHistory.size > 5) {
                            amplitudeHistory.removeAt(0)
                        }
                        _amplitudes.value = amplitudeHistory.toList()
                    }
                }
            }
        }
    }

    fun stop(): FloatArray {
        isRecording.set(false)
        
        val durationMs = System.currentTimeMillis() - recordingStartTimeMs
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        // Check for too-short recording
        if (durationMs < MINIMUM_RECORDING_MS) {
            Log.w(TAG, "Recording too short: ${durationMs}ms")
            onRecordingError?.invoke(RecordingError.TooShort)
            return FloatArray(0)
        }

        return synchronized(samples) {
            samples.toFloatArray().also { samples.clear() }
        }
    }

    fun isRecording(): Boolean = isRecording.get()
}
