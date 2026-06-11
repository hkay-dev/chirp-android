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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    /**
     * Identifies the live capture session. Bumped under [sampleLock] whenever a
     * session is published or torn down, so stale collectors and aborted start
     * attempts can detect that the recorder state no longer belongs to them.
     */
    private val sessionGeneration = AtomicLong(0L)

    /**
     * Per-call state of a [start] attempt, used so cleanup after a cancellation
     * or failure only ever tears down resources owned by that attempt — never a
     * previous session that must survive an early bail-out, and never a newer
     * session published by another caller.
     */
    private class StartAttempt {
        /** True once the attempt passed the early guards and owns the recorder reset. */
        var committed = false

        /** The readiness signal created by this attempt (never a successor's). */
        var ready: CompletableDeferred<Unit>? = null

        /** Resources created by this attempt but not yet published to the recorder. */
        var pendingRecord: AudioRecord? = null
        var pendingCaptureFile: File? = null
        var pendingCaptureOutput: BufferedOutputStream? = null

        /** Session generation this attempt published, or null if it never went live. */
        var publishedGeneration: Long? = null
    }

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
        val attempt = StartAttempt()
        return try {
            withContext(Dispatchers.IO) {
                startInternal(attempt)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation can surface at the withContext boundary after the
            // microphone is already recording; clean up before propagating so
            // the AudioRecord is never left hot. Skip attempts that bailed out
            // early (e.g. a previous session is still active and must survive).
            // Cleanup stays on IO: abortStart does AudioRecord teardown and
            // disk I/O that must not run on the caller's (often Main) thread.
            if (attempt.committed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    abortStart(attempt, e)
                }
            }
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startInternal(attempt: StartAttempt): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            hasError = true
            onRecordingError?.invoke(RecordingError.PermissionDenied)
            return false
        }
        if (isRecording.get()) return false

        // Reset synchronization
        attempt.committed = true
        val ready = CompletableDeferred<Unit>()
        attempt.ready = ready
        recordingReady = ready
        hasError = false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            ready.completeExceptionally(IllegalStateException("Invalid buffer size"))
            return false
        }

        return try {
            var retryCount = 0
            val maxRetries = 3
            var initException: Exception? = null

            while (retryCount < maxRetries) {
                try {
                    attempt.pendingRecord =
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
                    if (attempt.pendingRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        break
                    }
                    attempt.pendingRecord?.release()
                    attempt.pendingRecord = null
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    initException = e
                    attempt.pendingRecord?.release()
                    attempt.pendingRecord = null
                }

                retryCount++
                if (retryCount < maxRetries) {
                    delay(150)
                }
            }

            val record = attempt.pendingRecord
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                attempt.pendingRecord = null
                ready.completeExceptionally(initException ?: IllegalStateException("AudioRecord not initialized after retries"))
                return false
            }

            if (captureStorageMode == CaptureStorageMode.FileBacked) {
                val captureFile = createCaptureFile()
                attempt.pendingCaptureFile = captureFile
                attempt.pendingCaptureOutput = BufferedOutputStream(FileOutputStream(captureFile))
            }
            waveformBuffer.clear()
            _sampleCountFlow.value = 0L
            record.startRecording()
            synchronized(sampleLock) {
                // Publish the session atomically: from here on the shared state
                // belongs to this attempt and stale owners see a new generation.
                sampleCount = 0
                resetFileBackedCaptureLocked(deleteExisting = true)
                sampleFile = attempt.pendingCaptureFile
                sampleOutput = attempt.pendingCaptureOutput
                audioRecord = record
                attempt.pendingRecord = null
                attempt.pendingCaptureFile = null
                attempt.pendingCaptureOutput = null
                recordingStartTimeMs = SystemClock.elapsedRealtime()
                attempt.publishedGeneration = sessionGeneration.incrementAndGet()
                isRecording.set(true)
            }

            // Signal that recording is ready for collection
            ready.complete(Unit)

            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Clean up before rethrowing so a cancellation that lands after
            // AudioRecord.startRecording() cannot leave the microphone hot.
            abortStart(attempt, e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            abortStart(attempt, e)
            false
        }
    }

    /**
     * Aborts an in-flight or just-completed start attempt. Only tears down what
     * the attempt still owns: unpublished resources are released directly, and
     * a published session is stopped only while its generation is current, so a
     * newer session started by another caller is never torn down and only the
     * attempt's own readiness signal is completed. Safe to call multiple times.
     */
    private fun abortStart(
        attempt: StartAttempt,
        cause: Throwable,
    ) {
        val publishedGeneration = attempt.publishedGeneration
        if (publishedGeneration != null) {
            synchronized(sampleLock) {
                if (sessionGeneration.get() == publishedGeneration) {
                    stopAudioRecord()
                    sampleCount = 0
                    resetFileBackedCaptureLocked(deleteExisting = true)
                }
            }
        } else {
            runCatching { attempt.pendingRecord?.stop() }
            attempt.pendingRecord?.release()
            attempt.pendingRecord = null
            runCatching { attempt.pendingCaptureOutput?.close() }
            attempt.pendingCaptureOutput = null
            runCatching { attempt.pendingCaptureFile?.delete() }
            attempt.pendingCaptureFile = null
        }
        // No-op if start() already completed it (boundary cancellation case).
        attempt.ready?.completeExceptionally(cause)
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

            val record: AudioRecord?
            val collectGeneration: Long
            synchronized(sampleLock) {
                record = audioRecord
                collectGeneration = sessionGeneration.get()
            }
            if (record == null) return@withContext
            val buffer = FloatArray(1024)
            hasError = false

            while (isActive && isRecording.get() && sessionGeneration.get() == collectGeneration) {
                val readResult = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

                // Check for errors
                when {
                    readResult == AudioRecord.ERROR_INVALID_OPERATION -> {
                        failCollect(collectGeneration, RecordingError.InvalidOperation)
                        return@withContext
                    }

                    readResult == AudioRecord.ERROR_BAD_VALUE -> {
                        failCollect(collectGeneration, RecordingError.BadValue)
                        return@withContext
                    }

                    readResult == AudioRecord.ERROR_DEAD_OBJECT -> {
                        failCollect(collectGeneration, RecordingError.DeadObject)
                        return@withContext
                    }

                    readResult < 0 -> {
                        failCollect(collectGeneration, RecordingError.Generic(readResult))
                        return@withContext
                    }

                    readResult > 0 -> {
                        var writeFailed = false
                        var writeFailureEndedSession = false
                        // Normal case - process samples
                        synchronized(sampleLock) {
                            if (sessionGeneration.get() != collectGeneration) {
                                // Session was stopped or superseded while this
                                // read was in flight; the state is no longer ours.
                                return@withContext
                            }
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
                                            // End the session atomically with the
                                            // failure so a concurrently landing
                                            // stop() cannot suppress the report.
                                            writeFailureEndedSession = endSessionLocked()
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
                            if (writeFailureEndedSession) {
                                onRecordingError?.invoke(RecordingError.StorageUnavailable)
                            }
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

    private fun stopAudioRecord(): Long =
        synchronized(sampleLock) {
            // Invalidate stale collectors and aborts for the ending session.
            sessionGeneration.incrementAndGet()
            isRecording.set(false)
            val durationMs = SystemClock.elapsedRealtime() - recordingStartTimeMs
            runCatching { audioRecord?.stop() }
            audioRecord?.release()
            audioRecord = null
            durationMs
        }

    /**
     * Shared cleanup for abnormal collectSamples exits: stops and releases the
     * AudioRecord so the microphone never stays hot and discards any partial
     * capture, including file-backed temp files. Acts only while [generation]
     * still identifies the live session — a stale collector whose session was
     * already stopped or superseded must not tear down its successor — and
     * reports the error only if stop() has not already ended the session
     * (reads racing a stop() can surface spurious errors). Leaves the recorder
     * safe for a later start().
     */
    private fun failCollect(
        generation: Long,
        error: RecordingError,
    ) {
        val wasRecording =
            synchronized(sampleLock) {
                if (sessionGeneration.get() != generation) return
                endSessionLocked()
            }
        if (wasRecording) {
            onRecordingError?.invoke(error)
        }
    }

    /**
     * Ends the live session while holding [sampleLock]: marks the error flag,
     * stops and releases the AudioRecord, and discards any partial capture in
     * memory or on disk. Returns whether a session was still active so callers
     * report errors only for sessions that stop() had not already ended.
     */
    private fun endSessionLocked(): Boolean {
        val wasRecording = isRecording.getAndSet(false)
        if (wasRecording) {
            hasError = true
        }
        stopAudioRecord()
        sampleCount = 0
        resetFileBackedCaptureLocked(deleteExisting = true)
        return wasRecording
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
