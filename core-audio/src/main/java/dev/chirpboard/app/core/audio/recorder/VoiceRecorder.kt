package dev.chirpboard.app.core.audio.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.recording.WaveformBuffer
import java.io.BufferedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Errors that can occur during audio recording.
 */
sealed class RecordingError(
    val userMessage: String,
) {
    object InvalidOperation : RecordingError("Microphone not ready")

    object BadValue : RecordingError("Recording configuration error")

    object DeadObject : RecordingError("Microphone disconnected")

    data class Generic(
        val code: Int,
    ) : RecordingError("Recording failed (code: $code)")

    object TooShort : RecordingError("Recording too short")

    object PermissionDenied : RecordingError("Microphone permission denied")
}

/**
 * Handles audio recording for keyboard voice input.
 * Records PCM float samples at 16kHz mono for speech recognition.
 */
class VoiceRecorder(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val inputDeviceSelector: AudioInputDeviceSelector? = null,
    private val captureStorageMode: CaptureStorageMode = CaptureStorageMode.InMemory,
) : Closeable {
    companion object {
        private const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 16000
        const val MINIMUM_RECORDING_MS = 300L
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        /** Amplitude debounce interval - ~60fps is sufficient for visual smoothness on 120Hz displays */
        private const val AMPLITUDE_DEBOUNCE_MS = 16L
        const val MAX_SAMPLE_CAPACITY = SAMPLE_RATE * 60 * 10 // 10 minutes
        private const val INITIAL_SAMPLE_CAPACITY = SAMPLE_RATE * 60
    }

    enum class CaptureStorageMode {
        InMemory,
        FileBacked,
    }

    data class CapturedPcmFloatFile(
        val file: File,
        val sampleRate: Int,
        val sampleCount: Int,
    )

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var samples = FloatArray(INITIAL_SAMPLE_CAPACITY) // Pre-allocate 1 min
    private var sampleCount = 0
    private var sampleFile: File? = null
    private var sampleOutput: BufferedOutputStream? = null
    private val sampleLock = Any()

    // Synchronization for race condition between start() and collectSamples()
    private var recordingReady = CompletableDeferred<Unit>()
    private var recordingStartTimeMs: Long = 0

    /** Microphone gain multiplier (1.0 = no boost, 2.0 = double volume, etc.) */
    var gainMultiplier: Float = 1.0f

    /** Callback invoked when a recording error occurs */
    var onRecordingError: ((RecordingError) -> Unit)? = null

    /** Callback invoked when recording limit is reached */
    var onLimitReached: (() -> Unit)? = null

    /** Whether an error occurred during the current recording session */
    private var hasError = false

    // Coroutine scope for debounced flow
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val waveformBuffer = WaveformBuffer(42)
    private val _sampleCountFlow = MutableStateFlow(0L)
    val sampleCountFlow: StateFlow<Long> = _sampleCountFlow.asStateFlow()

    @SuppressLint("MissingPermission")
    suspend fun start(): Boolean =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                hasError = true
                onRecordingError?.invoke(RecordingError.PermissionDenied)
                return@withContext false
            }
            if (isRecording.get()) return@withContext false

            // Reset synchronization
            recordingReady = CompletableDeferred()
            hasError = false

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                recordingReady.completeExceptionally(IllegalStateException("Invalid buffer size"))
                return@withContext false
            }

            try {
                var retryCount = 0
                val maxRetries = 3
                var initException: Exception? = null

                while (retryCount < maxRetries) {
                    try {
                        audioRecord =
                            inputDeviceSelector?.let { selector ->
                                selector.buildAudioRecord(
                                    audioSource = MediaRecorder.AudioSource.MIC,
                                    sampleRate = SAMPLE_RATE,
                                    channelConfig = CHANNEL_CONFIG,
                                    audioFormat = AUDIO_FORMAT,
                                    bufferSize = bufferSize * 2,
                                )
                            } ?: AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                SAMPLE_RATE,
                                CHANNEL_CONFIG,
                                AUDIO_FORMAT,
                                bufferSize * 2,
                            )
                        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                            break
                        }
                        audioRecord?.release()
                        audioRecord = null
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        initException = e
                        audioRecord?.release()
                        audioRecord = null
                    }

                    retryCount++
                    if (retryCount < maxRetries) {
                        delay(150)
                    }
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    recordingReady.completeExceptionally(initException ?: IllegalStateException("AudioRecord not initialized after retries"))
                    return@withContext false
                }

                synchronized(sampleLock) {
                    sampleCount = 0
                    resetFileBackedCaptureLocked(deleteExisting = true)
                    if (captureStorageMode == CaptureStorageMode.FileBacked) {
                        val captureFile = createCaptureFile()
                        sampleFile = captureFile
                        sampleOutput = BufferedOutputStream(FileOutputStream(captureFile))
                    }
                }
                waveformBuffer.clear()
                _sampleCountFlow.value = 0L
                audioRecord?.startRecording()
                isRecording.set(true)
                recordingStartTimeMs = System.currentTimeMillis()

                // Signal that recording is ready for collection
                recordingReady.complete(Unit)

                true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to start recording", e)
                audioRecord?.release()
                audioRecord = null
                synchronized(sampleLock) {
                    resetFileBackedCaptureLocked(deleteExisting = true)
                }
                recordingReady.completeExceptionally(e)
                false
            }
        }

    suspend fun collectSamples() =
        withContext(Dispatchers.IO) {
            // Wait for start() to complete before collecting
            try {
                recordingReady.await()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
                        if (isRecording.get()) {
                            hasError = true
                            onRecordingError?.invoke(RecordingError.InvalidOperation)
                        }
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
                        synchronized(sampleLock) {
                            val spaceLeft = MAX_SAMPLE_CAPACITY - sampleCount
                            val toProcess = minOf(readResult, spaceLeft)

                            if (toProcess > 0) {
                                when (captureStorageMode) {
                                    CaptureStorageMode.InMemory -> {
                                        if (sampleCount + toProcess > samples.size) {
                                            if (samples.size < MAX_SAMPLE_CAPACITY) {
                                                samples =
                                                    samples.copyOf(
                                                        minOf(
                                                            MAX_SAMPLE_CAPACITY,
                                                            maxOf(samples.size * 2, sampleCount + toProcess),
                                                        ),
                                                    )
                                            }
                                        }

                                        for (i in 0 until toProcess) {
                                            samples[sampleCount + i] = boostedSample(buffer[i])
                                        }
                                    }

                                    CaptureStorageMode.FileBacked -> {
                                        writeFloatSamplesLocked(buffer, toProcess)
                                    }
                                }
                                sampleCount += toProcess
                            }

                            if (sampleCount >= MAX_SAMPLE_CAPACITY && isRecording.get()) {
                                isRecording.set(false)
                                onLimitReached?.invoke()
                                return@withContext
                            }
                        }
                        // Calculate amplitude for visualization (RMS of buffer)
                        var sum = 0f
                        for (i in 0 until readResult) {
                            sum += abs(buffer[i] * gainMultiplier)
                        }
                        val amplitude = (sum / readResult).coerceIn(0f, 1f)
                        waveformBuffer.add(amplitude)
                        _sampleCountFlow.value += 1L
                    }
                }
            }
        }

    fun stop(): FloatArray {
        val durationMs = stopAudioRecord()

        if (captureStorageMode == CaptureStorageMode.FileBacked) {
            synchronized(sampleLock) {
                resetFileBackedCaptureLocked(deleteExisting = true)
                sampleCount = 0
            }
            return FloatArray(0)
        }

        // Check for too-short recording
        if (durationMs < MINIMUM_RECORDING_MS) {
            Log.w(TAG, "Recording too short: ${durationMs}ms")
            onRecordingError?.invoke(RecordingError.TooShort)
            return FloatArray(0)
        }

        return synchronized(sampleLock) {
            val capturedSamples = samples.copyOf(sampleCount)
            sampleCount = 0
            if (samples.size != INITIAL_SAMPLE_CAPACITY) {
                samples = FloatArray(INITIAL_SAMPLE_CAPACITY)
            }
            capturedSamples
        }
    }

    fun stopToFileBacked(): CapturedPcmFloatFile? {
        val durationMs = stopAudioRecord()

        if (captureStorageMode != CaptureStorageMode.FileBacked) {
            return null
        }

        return synchronized(sampleLock) {
            closeSampleOutputLocked()
            val file = sampleFile
            val count = sampleCount
            sampleFile = null
            sampleCount = 0

            if (durationMs < MINIMUM_RECORDING_MS || count <= 0 || file == null) {
                if (durationMs < MINIMUM_RECORDING_MS) {
                    Log.w(TAG, "Recording too short: ${durationMs}ms")
                    onRecordingError?.invoke(RecordingError.TooShort)
                }
                runCatching { file?.delete() }
                null
            } else {
                CapturedPcmFloatFile(
                    file = file,
                    sampleRate = SAMPLE_RATE,
                    sampleCount = count,
                )
            }
        }
    }

    fun cancelCapture() {
        stopAudioRecord()
        synchronized(sampleLock) {
            sampleCount = 0
            resetFileBackedCaptureLocked(deleteExisting = true)
            if (samples.size != INITIAL_SAMPLE_CAPACITY) {
                samples = FloatArray(INITIAL_SAMPLE_CAPACITY)
            }
        }
    }

    fun isRecording(): Boolean = isRecording.get()

    override fun close() {
        scope.cancel()
        synchronized(sampleLock) {
            resetFileBackedCaptureLocked(deleteExisting = true)
        }
        audioRecord?.release()
        audioRecord = null
    }

    private fun stopAudioRecord(): Long {
        isRecording.set(false)
        val durationMs = System.currentTimeMillis() - recordingStartTimeMs
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
        return durationMs
    }

    private fun boostedSample(sample: Float): Float = (sample * gainMultiplier).coerceIn(-1f, 1f)

    private fun createCaptureFile(): File {
        val dir = File(context.cacheDir, "keyboard-capture").apply { mkdirs() }
        return File.createTempFile("dictation-", ".f32pcm", dir)
    }

    private fun writeFloatSamplesLocked(
        buffer: FloatArray,
        count: Int,
    ) {
        val output = sampleOutput ?: return
        val byteBuffer = ByteBuffer.allocate(count * java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) {
            byteBuffer.putFloat(boostedSample(buffer[index]))
        }
        output.write(byteBuffer.array())
    }

    private fun resetFileBackedCaptureLocked(deleteExisting: Boolean) {
        closeSampleOutputLocked()
        if (deleteExisting) {
            runCatching { sampleFile?.delete() }
        }
        sampleFile = null
    }

    private fun closeSampleOutputLocked() {
        runCatching { sampleOutput?.flush() }
        runCatching { sampleOutput?.close() }
        sampleOutput = null
    }
}
