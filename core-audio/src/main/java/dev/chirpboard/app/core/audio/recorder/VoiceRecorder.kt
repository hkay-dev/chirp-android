package dev.chirpboard.app.core.audio.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.recording.WaveformBuffer
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
    object StorageUnavailable : RecordingError("Recording storage unavailable")

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

        /** Cache subdirectory where file-backed dictation captures are written. */
        const val KEYBOARD_CAPTURE_CACHE_DIR = "keyboard-capture"

        /** Filename prefix of file-backed dictation capture temp files. */
        const val DICTATION_CAPTURE_FILE_PREFIX = "dictation-"

        /** Filename suffix of file-backed dictation capture temp files. */
        const val DICTATION_CAPTURE_FILE_SUFFIX = ".f32pcm"
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

    suspend fun start(): Boolean {
        var attemptCommitted = false
        return try {
            withContext(Dispatchers.IO) {
                startInternal(onAttemptCommitted = { attemptCommitted = true })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation can surface at the withContext boundary after the
            // microphone is already recording; clean up before propagating so
            // the AudioRecord is never left hot. Skip attempts that bailed out
            // early (e.g. a previous session is still active and must survive).
            if (attemptCommitted) {
                abortStart(e)
            }
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startInternal(onAttemptCommitted: () -> Unit): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            hasError = true
            onRecordingError?.invoke(RecordingError.PermissionDenied)
            return false
        }
        if (isRecording.get()) return false

        // Reset synchronization
        onAttemptCommitted()
        recordingReady = CompletableDeferred()
        hasError = false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            recordingReady.completeExceptionally(IllegalStateException("Invalid buffer size"))
            return false
        }

        return try {
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
                return false
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
            recordingStartTimeMs = SystemClock.elapsedRealtime()

            // Signal that recording is ready for collection
            recordingReady.complete(Unit)

            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Clean up before rethrowing so a cancellation that lands after
            // AudioRecord.startRecording() cannot leave the microphone hot.
            abortStart(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            abortStart(e)
            false
        }
    }

    /**
     * Aborts an in-flight or just-completed start: stops and releases the
     * AudioRecord, clears any partial capture state, and unblocks collectors.
     * Safe to call multiple times.
     */
    private fun abortStart(cause: Throwable) {
        stopAudioRecord()
        synchronized(sampleLock) {
            sampleCount = 0
            resetFileBackedCaptureLocked(deleteExisting = true)
        }
        // No-op if start() already completed it (boundary cancellation case).
        recordingReady.completeExceptionally(cause)
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
                        failCollect(RecordingError.InvalidOperation)
                        return@withContext
                    }

                    readResult == AudioRecord.ERROR_BAD_VALUE -> {
                        failCollect(RecordingError.BadValue)
                        return@withContext
                    }

                    readResult == AudioRecord.ERROR_DEAD_OBJECT -> {
                        failCollect(RecordingError.DeadObject)
                        return@withContext
                    }

                    readResult < 0 -> {
                        failCollect(RecordingError.Generic(readResult))
                        return@withContext
                    }

                    readResult > 0 -> {
                        var writeFailed = false
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
                                        writeFailed =
                                            runCatching {
                                                writeFloatSamplesLocked(buffer, toProcess)
                                            }.isFailure
                                        if (writeFailed) {
                                            resetFileBackedCaptureLocked(deleteExisting = true)
                                            return@synchronized
                                        }
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
                        if (writeFailed) {
                            Log.e(TAG, "Failed to write file-backed capture samples")
                            failCollect(RecordingError.StorageUnavailable)
                            return@withContext
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

        if (hasError) {
            clearInMemoryCapture()
            hasError = false
            return FloatArray(0)
        }

        if (durationMs < MINIMUM_RECORDING_MS) {
            Log.w(TAG, "Recording too short: ${durationMs}ms")
            clearInMemoryCapture()
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

            val failed = hasError
            hasError = false

            if (failed || durationMs < MINIMUM_RECORDING_MS || count <= 0 || file == null) {
                if (!failed && durationMs < MINIMUM_RECORDING_MS) {
                    Log.w(TAG, "Recording too short: ${durationMs}ms")
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
        stopAudioRecord()
        scope.cancel()
        synchronized(sampleLock) {
            sampleCount = 0
            resetFileBackedCaptureLocked(deleteExisting = true)
        }
    }

    private fun clearInMemoryCapture() {
        synchronized(sampleLock) {
            sampleCount = 0
            if (samples.size != INITIAL_SAMPLE_CAPACITY) {
                samples = FloatArray(INITIAL_SAMPLE_CAPACITY)
            }
        }
    }

    private fun stopAudioRecord(): Long {
        isRecording.set(false)
        val durationMs = SystemClock.elapsedRealtime() - recordingStartTimeMs
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
        return durationMs
    }

    /**
     * Shared cleanup for abnormal collectSamples exits: stops and releases the
     * AudioRecord so the microphone never stays hot, and reports the error only
     * if stop() has not already ended the session (reads racing a stop() can
     * surface spurious errors). Leaves the recorder safe for a later start().
     */
    private fun failCollect(error: RecordingError) {
        val wasRecording = isRecording.getAndSet(false)
        stopAudioRecord()
        if (wasRecording) {
            hasError = true
            onRecordingError?.invoke(error)
        }
    }

    private fun boostedSample(sample: Float): Float = (sample * gainMultiplier).coerceIn(-1f, 1f)

    private fun createCaptureFile(): File {
        val dir = File(context.cacheDir, KEYBOARD_CAPTURE_CACHE_DIR).apply { mkdirs() }
        return File.createTempFile(DICTATION_CAPTURE_FILE_PREFIX, DICTATION_CAPTURE_FILE_SUFFIX, dir)
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
