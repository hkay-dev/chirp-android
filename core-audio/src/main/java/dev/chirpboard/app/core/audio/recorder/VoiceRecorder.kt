package dev.chirpboard.app.core.audio.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import dev.chirpboard.app.core.audio.AudioGain
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.recording.WaveformBuffer
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    object CaptureStalled : RecordingError("Microphone stopped responding")

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
    private val captureOutputFactory: (File) -> OutputStream = { file -> FileOutputStream(file) },
    private val availableStorageBytes: (File) -> Long = { directory -> directory.usableSpace },
    private val reclaimEmergencyReserve: () -> Boolean = CaptureEmergencyReserve::reclaim,
) : Closeable {
    companion object {
        private const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 16000
        const val MINIMUM_RECORDING_MS = 300L
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        const val MAX_SAMPLE_CAPACITY = SAMPLE_RATE * 60 * 10 // 10 minutes

        /** File-backed keyboard capture can run for an hour without growing the process heap. */
        const val MAX_FILE_BACKED_SAMPLE_CAPACITY = SAMPLE_RATE * 60 * 60

        /** Growth capacity for the lazily-allocated in-memory sample buffer (1 minute at [SAMPLE_RATE]). */
        private const val INITIAL_SAMPLE_CAPACITY = SAMPLE_RATE * 60

        /** Sentinel empty buffer used when no in-memory capture is resident, so FileBacked mode never allocates. */
        private val EMPTY_SAMPLES = FloatArray(0)

        /** Number of float samples read from [AudioRecord] per blocking read. */
        private const val CAPTURE_READ_BUFFER_SIZE = 1024

        private const val FIRST_SAMPLES_READY_TIMEOUT_MS = 500L
        private const val CAPTURE_GAP_TOLERANCE_MS = 250L
        private const val TIMESTAMP_GAP_TOLERANCE_FRAMES = CAPTURE_READ_BUFFER_SIZE * 2L
        private const val MAX_DEAD_OBJECT_RECOVERIES = 1
        private const val MAX_WATCHDOG_RECOVERIES = 2
        private const val WATCHDOG_POLL_MS = 250L
        private const val READ_STALL_TIMEOUT_MS = 1_500L
        private const val ZERO_READ_LIMIT = 8

        /** Keeps enough headroom for ten minutes of float PCM plus filesystem overhead. */
        const val MIN_CAPTURE_FREE_BYTES = 48L * 1024L * 1024L

        /** Sustained all-zero input (4s) before [onSilenceStateChanged] reports silence. */
        const val SILENCE_WARNING_SAMPLES = SAMPLE_RATE * 4L

        /** Low-byte mask for splitting an int into little-endian bytes. */
        private const val BYTE_MASK = 0xFF

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

    data class CaptureIntegrityReport(
        val elapsedMs: Long,
        val capturedMs: Long,
        val estimatedGapMs: Long,
        val sampleCount: Int,
        val timestampGapFrames: Long = 0L,
        val timestampGapCount: Int = 0,
        val recorderRestartCount: Int = 0,
        val watchdogRestartCount: Int = 0,
    )

    internal data class TimestampGap(
        val missingFrames: Long,
        val hardwareFramePosition: Long,
    )

    internal enum class CaptureHealthIssue {
        StalledRead,
        RepeatedZeroReads,
    }

    internal class CaptureHealthMonitor(
        private val stallTimeoutMs: Long = READ_STALL_TIMEOUT_MS,
        private val zeroReadLimit: Int = ZERO_READ_LIMIT,
    ) {
        private var readStartedAtMs: Long? = null
        private var consecutiveZeroReads = 0
        private var pendingIssue: CaptureHealthIssue? = null

        @Synchronized
        fun onReadStarted(nowMs: Long) {
            readStartedAtMs = nowMs
        }

        @Synchronized
        fun onReadCompleted(result: Int) {
            readStartedAtMs = null
            consecutiveZeroReads = if (result == 0) consecutiveZeroReads + 1 else 0
            if (consecutiveZeroReads >= zeroReadLimit) pendingIssue = CaptureHealthIssue.RepeatedZeroReads
        }

        @Synchronized
        fun issueAt(nowMs: Long): CaptureHealthIssue? {
            val stalled = readStartedAtMs?.let { nowMs - it >= stallTimeoutMs } == true
            return if (stalled) CaptureHealthIssue.StalledRead else pendingIssue
        }

        @Synchronized
        fun markRestart() {
            readStartedAtMs = null
            consecutiveZeroReads = 0
            pendingIssue = null
        }
    }

    internal class CaptureBufferPool(
        sampleCapacity: Int = CAPTURE_READ_BUFFER_SIZE,
    ) {
        val readSamples = FloatArray(sampleCapacity)
        val pcmBytes = ByteArray(sampleCapacity * java.lang.Float.BYTES)
    }

    /** Thread-safe per-session continuity state. The capture thread is its only writer. */
    internal class CaptureContinuityTracker(
        private val sampleRate: Int = SAMPLE_RATE,
        private val toleranceFrames: Long = TIMESTAMP_GAP_TOLERANCE_FRAMES,
    ) {
        private var previousFramePosition: Long? = null
        private var previousNanoTime: Long? = null
        private var previousCapturedFrames = 0L
        private var gapFrames = 0L
        private var gapCount = 0
        private var restartCount = 0

        @Synchronized
        fun observe(
            framePosition: Long,
            nanoTime: Long,
            capturedFrames: Long,
        ): TimestampGap? {
            val priorPosition = previousFramePosition
            val priorNanos = previousNanoTime
            val priorCaptured = previousCapturedFrames
            previousFramePosition = framePosition
            previousNanoTime = nanoTime
            previousCapturedFrames = capturedFrames
            if (priorPosition == null || priorNanos == null || framePosition < priorPosition) return null

            val hardwareDelta = framePosition - priorPosition
            val deliveredDelta = (capturedFrames - priorCaptured).coerceAtLeast(0L)
            val clockFrames =
                (((nanoTime - priorNanos).coerceAtLeast(0L) * sampleRate) / 1_000_000_000L)
            val unreadHardwareFrames = hardwareDelta - deliveredDelta - toleranceFrames
            val stalledHardwareFrames = clockFrames - hardwareDelta - toleranceFrames
            val missing = maxOf(unreadHardwareFrames, stalledHardwareFrames, 0L)
            if (missing == 0L) return null
            gapFrames += missing
            gapCount += 1
            return TimestampGap(missingFrames = missing, hardwareFramePosition = framePosition)
        }

        @Synchronized
        fun markRecorderRestart() {
            restartCount += 1
            previousFramePosition = null
            previousNanoTime = null
        }

        @Synchronized
        fun snapshot(): Triple<Long, Int, Int> = Triple(gapFrames, gapCount, restartCount)
    }

    private var audioRecord: AudioRecord? = null
    private var collectorJob: Job? = null
    private var watchdogJob: Job? = null
    private var firstSamplesReady = CompletableDeferred<Boolean>()
    @Volatile private var lastIntegrityReport: CaptureIntegrityReport? = null
    @Volatile private var continuityTracker = CaptureContinuityTracker()
    private var nativeBufferSizeBytes = 0
    private val recoveryMutex = Mutex()
    @Volatile private var healthMonitor = CaptureHealthMonitor()
    private val watchdogRestartCount = AtomicInteger(0)

    /**
     * Active-device publication token for the live capture (from
     * [AudioInputDeviceSelector.buildAudioRecord]); passed back to
     * [AudioInputDeviceSelector.clearActiveDevice] in [stopAudioRecord] so every
     * VoiceRecorder surface (IME + recognition) clears the selector's published
     * device exactly once, and a late clear can never clobber a newer session's
     * publication. Guarded by [sampleLock].
     */
    private var selectorSessionToken: Long? = null
    private val isRecording = AtomicBoolean(false)

    /**
     * In-memory PCM sample buffer. Starts as an empty sentinel and is only
     * allocated lazily on first InMemory capture, grown on demand, and trimmed
     * back to the sentinel after stop/cancel/close so the multi-MB working set
     * does not sit resident in the IME/recognition processes between dictations.
     * FileBacked mode never allocates it.
     */
    private var samples = EMPTY_SAMPLES
    @Volatile private var sampleCount = 0
    private var sampleFile: File? = null
    private var sampleOutput: OutputStream? = null

    /** Control state stays separate from pooled buffers and the file writer lock. */
    private val sampleLock = Any()
    /** File writes are serialized separately so slow storage never holds recorder control state. */
    private val captureWriteLock = Any()

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
    private class StartAttempt(
        val requestedCaptureFile: File?,
    ) {
        /** True once the attempt passed the early guards and owns the recorder reset. */
        var committed = false

        /** The readiness signal created by this attempt (never a successor's). */
        var ready: CompletableDeferred<Unit>? = null
        var firstSamplesReady: CompletableDeferred<Boolean>? = null

        /** Resources created by this attempt but not yet published to the recorder. */
        var pendingRecord: AudioRecord? = null
        var pendingCaptureFile: File? = null
        var pendingCaptureOutput: OutputStream? = null

        /** Selector publication token from buildAudioRecord, until published or cleared. */
        var pendingSessionToken: Long? = null

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

    /**
     * Callback invoked from the collection coroutine when the capture transitions into or
     * out of sustained digital silence (every sample exactly zero for
     * [SILENCE_WARNING_SAMPLES]). Pure zeros are the signature of a silenced AudioRecord
     * client: another app holds the mic under the concurrent-capture policy or the mic
     * privacy toggle is off. Reads keep succeeding, so without this the session records
     * nothing while looking live.
     */
    var onSilenceStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Whether an error occurred during the current recording session. Volatile
     * because it is reset from start/collect threads outside [sampleLock] while
     * the stop paths read it under the lock.
     */
    @Volatile
    private var hasError = false

    /**
     * Test seam invoked in the gap between a stop path's two lock blocks (after
     * [stopAudioRecord], before the capture-state block) so tests can interleave
     * a racing [start] inside the otherwise microsecond-wide window. Never set
     * in production.
     */
    @VisibleForTesting
    internal var afterStopAudioRecordForTest: (() -> Unit)? = null

    val waveformBuffer = WaveformBuffer(42)
    private val _sampleCountFlow = MutableStateFlow(0L)
    val sampleCountFlow: StateFlow<Long> = _sampleCountFlow.asStateFlow()

    suspend fun start(
        fileBackedCaptureFile: File? = null,
        collectImmediately: Boolean = false,
    ): Boolean {
        require(fileBackedCaptureFile == null || captureStorageMode == CaptureStorageMode.FileBacked) {
            "A requested capture file needs file-backed capture mode"
        }
        val attempt = StartAttempt(fileBackedCaptureFile)
        return try {
            withContext(Dispatchers.IO) {
                startInternal(attempt, collectImmediately)
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
    private suspend fun startInternal(
        attempt: StartAttempt,
        collectImmediately: Boolean,
    ): Boolean {
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
        firstSamplesReady = CompletableDeferred<Boolean>().also { attempt.firstSamplesReady = it }
        hasError = false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            ready.completeExceptionally(IllegalStateException("Invalid buffer size"))
            attempt.firstSamplesReady?.complete(false)
            return false
        }
        nativeBufferSizeBytes = bufferSize * 2

        if (captureStorageMode == CaptureStorageMode.FileBacked) {
            val captureDirectory =
                attempt.requestedCaptureFile?.parentFile
                    ?: File(context.cacheDir, KEYBOARD_CAPTURE_CACHE_DIR)
            val hasCaptureStorage =
                runCatching {
                    (captureDirectory.isDirectory || captureDirectory.mkdirs()) &&
                        availableStorageBytes(captureDirectory) >= MIN_CAPTURE_FREE_BYTES
                }.getOrElse { error ->
                    Log.e(TAG, "Could not inspect capture storage", error)
                    false
                }
            if (!hasCaptureStorage) {
                hasError = true
                ready.completeExceptionally(IllegalStateException("Insufficient capture storage"))
                attempt.firstSamplesReady?.complete(false)
                onRecordingError?.invoke(RecordingError.StorageUnavailable)
                return false
            }
        }

        return try {
            var retryCount = 0
            val maxRetries = 3
            var initException: Exception? = null

            while (retryCount < maxRetries) {
                try {
                    // VOICE_RECOGNITION: every VoiceRecorder surface feeds ASR, and this
                    // source gives recognition-tuned processing (predictable AGC, no
                    // phoneme-smearing noise suppression) instead of generic MIC defaults.
                    // buildAudioRecord also suspends through Bluetooth-SCO
                    // communication-device activation (bounded ~2s) before the record
                    // exists, so startRecording() below is gated on the SCO route being
                    // live or already fallen back to default routing (MIC-006) — these
                    // short init retries never need to cover SCO bring-up themselves.
                    attempt.pendingRecord =
                        inputDeviceSelector?.let { selector ->
                            val session =
                                selector.buildAudioRecord(
                                    audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                    sampleRate = SAMPLE_RATE,
                                    channelConfig = CHANNEL_CONFIG,
                                    audioFormat = AUDIO_FORMAT,
                                    bufferSize = nativeBufferSizeBytes,
                                )
                            attempt.pendingSessionToken = session.sessionToken
                            session.record
                        } ?: AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            nativeBufferSizeBytes,
                        )
                    if (attempt.pendingRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        break
                    }
                    attempt.pendingRecord?.release()
                    attempt.pendingRecord = null
                    // Token-clear the failed attempt's publication before the retry
                    // overwrites the token: an unreleased token would otherwise leak
                    // its communication-device hold (MIC-006).
                    clearPendingSessionToken(attempt)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    initException = e
                    attempt.pendingRecord?.release()
                    attempt.pendingRecord = null
                    clearPendingSessionToken(attempt)
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
                clearPendingSessionToken(attempt)
                ready.completeExceptionally(initException ?: IllegalStateException("AudioRecord not initialized after retries"))
                return false
            }

            if (captureStorageMode == CaptureStorageMode.FileBacked) {
                val captureFile =
                    attempt.requestedCaptureFile?.also { requested ->
                        check(requested.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                            "Could not create the requested capture directory"
                        }
                        check(!requested.exists() || (requested.isFile && requested.length() == 0L)) {
                            "Requested capture file already has audio"
                        }
                    } ?: createCaptureFile()
                attempt.pendingCaptureFile = captureFile
                // A process kill cannot flush a BufferedOutputStream's user-space tail. Writing
                // each AudioRecord block straight to the file descriptor keeps every completed
                // write recoverable by the live-capture journal.
                attempt.pendingCaptureOutput = captureOutputFactory(captureFile)
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
                selectorSessionToken = attempt.pendingSessionToken
                attempt.pendingRecord = null
                attempt.pendingCaptureFile = null
                attempt.pendingCaptureOutput = null
                attempt.pendingSessionToken = null
                recordingStartTimeMs = SystemClock.elapsedRealtime()
                continuityTracker = CaptureContinuityTracker()
                healthMonitor = CaptureHealthMonitor()
                watchdogRestartCount.set(0)
                attempt.publishedGeneration = sessionGeneration.incrementAndGet()
                isRecording.set(true)
            }

            // Track live reroutes for the rest of the session ([stopAudioRecord]
            // removes the listener before release); the first-read refresh in
            // collectSamples stays because the platform may not fire the listener
            // for the initial route on all OS versions.
            inputDeviceSelector?.observeRouting(record)

            // Signal that recording is ready for collection
            ready.complete(Unit)
            if (collectImmediately) {
                collectorJob = coroutineScope.launch(Dispatchers.IO) { collectSamples() }
            }

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
            clearPendingSessionToken(attempt)
            runCatching { attempt.pendingCaptureOutput?.close() }
            attempt.pendingCaptureOutput = null
            runCatching { attempt.pendingCaptureFile?.delete() }
            attempt.pendingCaptureFile = null
        }
        // No-op if start() already completed it (boundary cancellation case).
        attempt.ready?.completeExceptionally(cause)
        attempt.firstSamplesReady?.complete(false)
    }

    /**
     * Releases the selector publication a failed or aborted start attempt made
     * via [AudioInputDeviceSelector.buildAudioRecord] before it went live.
     * Token-aware, so a newer session's published device always survives.
     */
    private fun clearPendingSessionToken(attempt: StartAttempt) {
        attempt.pendingSessionToken?.let { token -> inputDeviceSelector?.clearActiveDevice(token) }
        attempt.pendingSessionToken = null
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

            val originalPriority =
                runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            try {
                var record: AudioRecord?
                val collectGeneration: Long
                val collectFirstSamplesReady: CompletableDeferred<Boolean>
                synchronized(sampleLock) {
                    record = audioRecord
                    collectGeneration = sessionGeneration.get()
                    collectFirstSamplesReady = firstSamplesReady
                }
                if (record == null) return@withContext
                // A stopped collector can remain blocked in AudioRecord.read() briefly after a
                // successor starts. Keep its native read and conversion buffers session-local so
                // that late return can never overwrite the successor's block.
                val buffers = CaptureBufferPool()
                val buffer = buffers.readSamples
                hasError = false
                var routingChecked = false
                var firstReadLogged = false
                var silentSampleRun = 0L
                var silenceNotified = false
                var deadObjectRecoveries = 0
                var capturedFramesForTimestamp = 0L
                val timestamp = AudioTimestamp()
                val monitor = healthMonitor
                startCaptureWatchdog(collectGeneration, monitor)

                while (isActive && isRecording.get() && sessionGeneration.get() == collectGeneration) {
                val activeRecord = record ?: return@withContext
                monitor.onReadStarted(SystemClock.elapsedRealtime())
                val readResult = activeRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                monitor.onReadCompleted(readResult)

                val currentRecord = synchronized(sampleLock) { audioRecord }
                if (currentRecord !== activeRecord && sessionGeneration.get() == collectGeneration) {
                    record = currentRecord
                    routingChecked = false
                    continue
                }

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
                        if (!isRecording.get() || sessionGeneration.get() != collectGeneration) {
                            return@withContext
                        }
                        val replacement =
                            if (deadObjectRecoveries < MAX_DEAD_OBJECT_RECOVERIES) {
                                recoverAudioRecord(collectGeneration, activeRecord, "dead object")
                            } else {
                                null
                            }
                        if (replacement == null) {
                            failCollect(collectGeneration, RecordingError.DeadObject)
                            return@withContext
                        }
                        deadObjectRecoveries += 1
                        record = replacement
                        routingChecked = false
                        continue
                    }

                    readResult < 0 -> {
                        failCollect(collectGeneration, RecordingError.Generic(readResult))
                        return@withContext
                    }

                    readResult > 0 -> {
                        if (!firstReadLogged) {
                            firstReadLogged = true
                            runCatching {
                                Log.i(
                                    TAG,
                                    "First microphone samples arrived ${SystemClock.elapsedRealtime() - recordingStartTimeMs}ms after AudioRecord start",
                                )
                            }
                        }
                        val sampleCapacity =
                            when (captureStorageMode) {
                                CaptureStorageMode.InMemory -> MAX_SAMPLE_CAPACITY
                                CaptureStorageMode.FileBacked -> MAX_FILE_BACKED_SAMPLE_CAPACITY
                            }
                        val blockGain = gainMultiplier
                        var blockAbsSum = 0f
                        val writeFailure =
                            when (captureStorageMode) {
                                CaptureStorageMode.InMemory -> {
                                    synchronized(sampleLock) {
                                        if (sessionGeneration.get() != collectGeneration) return@withContext
                                        val toProcess = minOf(readResult, sampleCapacity - sampleCount)
                                        ensureInMemoryCapacityLocked(sampleCount + toProcess)
                                        for (i in 0 until toProcess) {
                                            val boosted = AudioGain.boost(buffer[i], blockGain)
                                            samples[sampleCount + i] = boosted
                                            blockAbsSum += abs(boosted)
                                        }
                                        sampleCount += toProcess
                                    }
                                    null
                                }

                                CaptureStorageMode.FileBacked ->
                                    runCatching {
                                        synchronized(captureWriteLock) {
                                            if (sessionGeneration.get() != collectGeneration) return@withContext
                                            val toProcess = minOf(readResult, sampleCapacity - sampleCount)
                                            if (toProcess > 0) {
                                                blockAbsSum =
                                                    writeFloatSamples(
                                                        buffer = buffer,
                                                        count = toProcess,
                                                        scratch = buffers.pcmBytes,
                                                        gain = blockGain,
                                                    )
                                                // A block becomes trusted only once its entire direct write returns.
                                                sampleCount += toProcess
                                            }
                                        }
                                    }.exceptionOrNull()
                            }

                        if (writeFailure != null) {
                            Log.e(TAG, "Failed to write file-backed capture samples", writeFailure)
                            val ended =
                                synchronized(sampleLock) {
                                    if (sessionGeneration.get() != collectGeneration) false else endSessionLocked()
                                }
                            if (ended) {
                                onRecordingError?.invoke(RecordingError.StorageUnavailable)
                            }
                            return@withContext
                        }
                        if (sampleCount >= sampleCapacity && isRecording.getAndSet(false)) {
                            onLimitReached?.invoke()
                            return@withContext
                        }
                        collectFirstSamplesReady.complete(true)
                        if (!routingChecked) {
                            routingChecked = true
                            inputDeviceSelector?.let { selector ->
                                runCatching { selector.refreshActiveDeviceFromRouting(activeRecord) }
                            }
                        }
                        capturedFramesForTimestamp += readResult
                        if (activeRecord.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            continuityTracker
                                .observe(
                                    framePosition = timestamp.framePosition,
                                    nanoTime = timestamp.nanoTime,
                                    capturedFrames = capturedFramesForTimestamp,
                                )
                                ?.let { gap ->
                                    Log.w(
                                        TAG,
                                        "Audio timestamp discontinuity: ${gap.missingFrames} frames " +
                                            "near hardware frame ${gap.hardwareFramePosition}",
                                    )
                                }
                        }
                        // Encoding/copying already touched every sample. Reuse that pass for the
                        // display amplitude so the urgent-audio thread does no redundant scan.
                        val amplitude = (blockAbsSum / readResult).coerceIn(0f, 1f)
                        waveformBuffer.add(amplitude)
                        _sampleCountFlow.value += 1L
                        if (blockAbsSum == 0f) {
                            silentSampleRun += readResult
                            if (!silenceNotified && silentSampleRun >= SILENCE_WARNING_SAMPLES) {
                                silenceNotified = true
                                onSilenceStateChanged?.invoke(true)
                            }
                        } else {
                            silentSampleRun = 0L
                            if (silenceNotified) {
                                silenceNotified = false
                                onSilenceStateChanged?.invoke(false)
                            }
                        }
                    }

                    readResult == 0 -> {
                        val issue = monitor.issueAt(SystemClock.elapsedRealtime())
                        if (issue != null) {
                            val replacement = recoverForHealthIssue(collectGeneration, activeRecord, issue, monitor)
                            if (replacement == null) {
                                failCollect(collectGeneration, RecordingError.CaptureStalled)
                                return@withContext
                            }
                            record = replacement
                            routingChecked = false
                        }
                    }
                }
                }
            } finally {
                originalPriority?.let { priority ->
                    runCatching { Process.setThreadPriority(priority) }
                }
            }
        }

    /**
     * Rebuilds a dead platform recorder once and atomically swaps it into the same logical
     * capture. The durable file and trusted sample count stay untouched, so every block before
     * the platform failure remains the prefix of the recovered recording.
     */
    @SuppressLint("MissingPermission")
    private suspend fun recoverAudioRecord(
        generation: Long,
        deadRecord: AudioRecord,
        reason: String,
    ): AudioRecord? =
        recoveryMutex.withLock {
            val stillCurrent = synchronized(sampleLock) { audioRecord === deadRecord && sessionGeneration.get() == generation }
            if (!stillCurrent) return@withLock null
            recoverAudioRecordUnlocked(generation, deadRecord, reason)
        }

    @SuppressLint("MissingPermission")
    private suspend fun recoverAudioRecordUnlocked(
        generation: Long,
        deadRecord: AudioRecord,
        reason: String,
    ): AudioRecord? {
        Log.w(TAG, "AudioRecord $reason; attempting in-place capture recovery")
        var replacement: AudioRecord? = null
        var replacementToken: Long? = null
        try {
            val builtRecord =
                inputDeviceSelector?.let { selector ->
                    val session =
                        selector.buildAudioRecord(
                            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            sampleRate = SAMPLE_RATE,
                            channelConfig = CHANNEL_CONFIG,
                            audioFormat = AUDIO_FORMAT,
                            bufferSize = nativeBufferSizeBytes,
                        )
                    replacementToken = session.sessionToken
                    session.record
                } ?: AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    nativeBufferSizeBytes,
                )
            replacement = builtRecord
            if (builtRecord.state != AudioRecord.STATE_INITIALIZED) {
                builtRecord.release()
                replacementToken?.let { inputDeviceSelector?.clearActiveDevice(it) }
                return null
            }
            builtRecord.startRecording()
        } catch (error: kotlinx.coroutines.CancellationException) {
            runCatching { replacement?.stop() }
            replacement?.release()
            replacementToken?.let { inputDeviceSelector?.clearActiveDevice(it) }
            throw error
        } catch (error: Exception) {
            runCatching { replacement?.stop() }
            replacement?.release()
            replacementToken?.let { inputDeviceSelector?.clearActiveDevice(it) }
            Log.e(TAG, "AudioRecord recovery failed", error)
            return null
        }
        val readyReplacement = checkNotNull(replacement)

        var swapped = false
        synchronized(sampleLock) {
            if (isRecording.get() && sessionGeneration.get() == generation && audioRecord === deadRecord) {
                val oldToken = selectorSessionToken
                audioRecord = readyReplacement
                selectorSessionToken = replacementToken
                // Complete the routing-listener handoff under the same lock used by stop. A stop
                // racing immediately after the swap must not release the replacement between the
                // swap and observeRouting(), which would register a listener on a dead recorder.
                inputDeviceSelector?.stopObservingRouting(deadRecord)
                runCatching { deadRecord.stop() }
                deadRecord.release()
                oldToken?.let { inputDeviceSelector?.clearActiveDevice(it) }
                inputDeviceSelector?.observeRouting(readyReplacement)
                continuityTracker.markRecorderRestart()
                swapped = true
            }
        }
        if (!swapped) {
            runCatching { readyReplacement.stop() }
            readyReplacement.release()
            replacementToken?.let { inputDeviceSelector?.clearActiveDevice(it) }
            return null
        }
        Log.i(TAG, "AudioRecord recovery resumed the existing logical capture")
        return readyReplacement
    }

    private fun startCaptureWatchdog(
        generation: Long,
        monitor: CaptureHealthMonitor,
    ) {
        watchdogJob?.cancel()
        watchdogJob =
            coroutineScope.launch(Dispatchers.IO) {
                while (isActive && isRecording.get() && sessionGeneration.get() == generation) {
                    delay(WATCHDOG_POLL_MS)
                    val issue = monitor.issueAt(SystemClock.elapsedRealtime()) ?: continue
                    val stalledRecord = synchronized(sampleLock) { audioRecord } ?: return@launch
                    val replacement = recoverForHealthIssue(generation, stalledRecord, issue, monitor)
                    if (replacement == null) {
                        val wasReplaced = synchronized(sampleLock) { audioRecord !== stalledRecord }
                        if (!wasReplaced) failCollect(generation, RecordingError.CaptureStalled)
                    }
                }
            }
    }

    private suspend fun recoverForHealthIssue(
        generation: Long,
        record: AudioRecord,
        issue: CaptureHealthIssue,
        monitor: CaptureHealthMonitor,
    ): AudioRecord? {
        if (watchdogRestartCount.get() >= MAX_WATCHDOG_RECOVERIES) return null
        val replacement = recoverAudioRecord(generation, record, "health watchdog detected ${issue.name}")
        if (replacement != null) {
            watchdogRestartCount.incrementAndGet()
            monitor.markRestart()
            return replacement
        }
        // The collector and watchdog can notice the same issue together. Recovery is serialized,
        // but the loser observes that its old record is no longer current. Treat the winner's
        // replacement as success instead of failing the healthy logical capture.
        val concurrentReplacement =
            synchronized(sampleLock) {
                audioRecord?.takeIf {
                    isRecording.get() && sessionGeneration.get() == generation && it !== record
                }
            }
        if (concurrentReplacement != null) {
            monitor.markRestart()
            return concurrentReplacement
        }
        return null
    }

    /**
     * Waits briefly for the first native microphone block. Capture is already live during this
     * wait, so callers can use it as the honest user-facing "speak now" boundary without putting
     * model loading or UI scheduling in front of AudioRecord.
     */
    suspend fun awaitFirstSamples(timeoutMs: Long = FIRST_SAMPLES_READY_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { firstSamplesReady.await() } == true

    fun activeFileBackedSnapshot(): CapturedPcmFloatFile? =
        synchronized(sampleLock) {
            if (!isRecording.get() || captureStorageMode != CaptureStorageMode.FileBacked) return@synchronized null
            val file = sampleFile ?: return@synchronized null
            CapturedPcmFloatFile(file = file, sampleRate = SAMPLE_RATE, sampleCount = sampleCount)
        }

    fun latestIntegrityReport(): CaptureIntegrityReport? = lastIntegrityReport

    fun stop(): FloatArray {
        val (durationMs, endedGeneration) = stopAudioRecord()
        afterStopAudioRecordForTest?.invoke()

        if (captureStorageMode == CaptureStorageMode.FileBacked) {
            synchronized(sampleLock) {
                // Mirror failCollect's discipline: a start() racing this stop
                // may have published a new session in the gap between the two
                // lock blocks, and its state is no longer ours to reset.
                if (sessionGeneration.get() == endedGeneration) {
                    resetFileBackedCaptureLocked(deleteExisting = true)
                    sampleCount = 0
                }
            }
            return FloatArray(0)
        }

        var failed = false
        val captured =
            synchronized(sampleLock) {
                // Mirror failCollect's discipline: a start() racing this stop
                // may have published a new session in the gap between the two
                // lock blocks; zeroing its capture would lose it from t=0.
                if (sessionGeneration.get() != endedGeneration) return FloatArray(0)
                failed = hasError
                hasError = false
                val capturedSamples =
                    if (failed || durationMs < MINIMUM_RECORDING_MS) {
                        FloatArray(0)
                    } else {
                        samples.copyOf(sampleCount)
                    }
                sampleCount = 0
                // Release the multi-MB buffer instead of re-allocating a 1-minute
                // one that may never be used again; the next start allocates lazily.
                samples = EMPTY_SAMPLES
                capturedSamples
            }
        if (!failed && durationMs < MINIMUM_RECORDING_MS) {
            Log.w(TAG, "Recording too short: ${durationMs}ms")
            onRecordingError?.invoke(RecordingError.TooShort)
        }
        return captured
    }

    fun stopToFileBacked(): CapturedPcmFloatFile? {
        val (durationMs, endedGeneration) = stopAudioRecord()
        afterStopAudioRecordForTest?.invoke()

        if (captureStorageMode != CaptureStorageMode.FileBacked) {
            return null
        }

        return synchronized(sampleLock) {
            // Mirror failCollect's discipline: a start() racing this stop may
            // have published a new session in the gap between the two lock
            // blocks; closing its output or taking its file would corrupt the
            // live capture.
            if (sessionGeneration.get() != endedGeneration) return null
            closeSampleOutputLocked()
            val file = sampleFile
            val count = sampleCount
            sampleFile = null
            sampleCount = 0

            val failed = hasError
            hasError = false

            if (count <= 0 || file == null || (!failed && durationMs < MINIMUM_RECORDING_MS)) {
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
        val endedGeneration = stopAudioRecord().endedGeneration
        afterStopAudioRecordForTest?.invoke()
        synchronized(sampleLock) {
            // No-op when a start() racing this cancel published a new session
            // in the gap between the two lock blocks (mirrors failCollect).
            if (sessionGeneration.get() != endedGeneration) return
            sampleCount = 0
            resetFileBackedCaptureLocked(deleteExisting = true)
            samples = EMPTY_SAMPLES
        }
    }

    fun isRecording(): Boolean = isRecording.get()

    override fun close() {
        val endedGeneration = stopAudioRecord().endedGeneration
        afterStopAudioRecordForTest?.invoke()
        synchronized(sampleLock) {
            // No-op when a start() racing this close published a new session
            // in the gap between the two lock blocks (mirrors failCollect).
            if (sessionGeneration.get() != endedGeneration) return
            sampleCount = 0
            resetFileBackedCaptureLocked(deleteExisting = true)
            samples = EMPTY_SAMPLES
        }
    }

    /** What [stopAudioRecord] left behind: the session duration and the generation it ended on. */
    private data class StopResult(
        val durationMs: Long,
        val endedGeneration: Long,
    )

    private fun stopAudioRecord(): StopResult =
        synchronized(sampleLock) {
            // Invalidate stale collectors and aborts for the ending session. The
            // bumped generation is returned so callers' follow-up lock blocks can
            // no-op when a concurrent start() published a newer session between
            // the two blocks (mirrors failCollect's discipline).
            val endedGeneration = sessionGeneration.incrementAndGet()
            isRecording.set(false)
            val durationMs = SystemClock.elapsedRealtime() - recordingStartTimeMs
            val capturedMs = (sampleCount * 1000L) / SAMPLE_RATE
            val (timestampGapFrames, timestampGapCount, restartCount) = continuityTracker.snapshot()
            val timestampGapMs = (timestampGapFrames * 1000L) / SAMPLE_RATE
            val integrityReport =
                CaptureIntegrityReport(
                    elapsedMs = durationMs,
                    capturedMs = capturedMs,
                    estimatedGapMs =
                        maxOf(
                            (durationMs - capturedMs - CAPTURE_GAP_TOLERANCE_MS).coerceAtLeast(0L),
                            timestampGapMs,
                        ),
                    sampleCount = sampleCount,
                    timestampGapFrames = timestampGapFrames,
                    timestampGapCount = timestampGapCount,
                    recorderRestartCount = restartCount,
                    watchdogRestartCount = watchdogRestartCount.get(),
                )
            lastIntegrityReport = integrityReport
            if (integrityReport.estimatedGapMs > 0L) {
                runCatching {
                    Log.w(
                        TAG,
                        "Capture integrity gap estimated at ${integrityReport.estimatedGapMs}ms " +
                            "(${integrityReport.sampleCount} samples across ${integrityReport.elapsedMs}ms, " +
                            "timestampGaps=${integrityReport.timestampGapCount}, " +
                            "restarts=${integrityReport.recorderRestartCount}, " +
                            "watchdogRestarts=${integrityReport.watchdogRestartCount})",
                    )
                }
            }
            audioRecord?.let { record ->
                // Routing-listener removal must precede release() so the
                // selector's per-record entry never leaks.
                inputDeviceSelector?.stopObservingRouting(record)
                runCatching { record.stop() }
                record.release()
            }
            audioRecord = null
            // Token-aware clear: every VoiceRecorder surface (IME + recognition)
            // clears the selector's published device exactly once, and a newer
            // session's publication always survives a late teardown.
            selectorSessionToken?.let { token -> inputDeviceSelector?.clearActiveDevice(token) }
            selectorSessionToken = null
            collectorJob?.cancel()
            collectorJob = null
            watchdogJob?.cancel()
            watchdogJob = null
            firstSamplesReady.complete(false)
            StopResult(
                durationMs = durationMs,
                endedGeneration = endedGeneration,
            )
        }

    /**
     * Shared cleanup for abnormal collectSamples exits: stops and releases the
     * AudioRecord so the microphone never stays hot. File-backed capture keeps
     * every fully written sample for rescue. Acts only while [generation]
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
     * stops and releases the AudioRecord, and keeps file-backed samples for a
     * later ownership handoff. Returns whether a session was still active so callers
     * report errors only for sessions that stop() had not already ended.
     */
    private fun endSessionLocked(): Boolean {
        val wasRecording = isRecording.getAndSet(false)
        if (wasRecording) {
            hasError = true
        }
        stopAudioRecord()
        if (captureStorageMode == CaptureStorageMode.FileBacked) {
            // A failing OutputStream may have written only part of the current block. sampleCount
            // advances after the whole write succeeds, so trim that uncertain tail and keep every
            // earlier block available to stopToFileBacked() for rescue.
            closeSampleOutputLocked()
            trimFileBackedCaptureToCountLocked()
        } else {
            sampleCount = 0
            resetFileBackedCaptureLocked(deleteExisting = true)
        }
        return wasRecording
    }

    /**
     * Applies the gain with a soft-knee limiter instead of hard clipping: boosted speech
     * peaks are compressed smoothly toward full scale rather than squared off, which kept
     * distortion out of both the recognizer input and the rescued audio.
     */
    /**
     * Ensures the in-memory [samples] buffer can hold [requiredSize] floats,
     * allocating it lazily on first use (seeded at [INITIAL_SAMPLE_CAPACITY])
     * and doubling on demand, capped at [MAX_SAMPLE_CAPACITY]. Must be called
     * under [sampleLock].
     */
    private fun ensureInMemoryCapacityLocked(requiredSize: Int) {
        if (requiredSize <= samples.size || samples.size >= MAX_SAMPLE_CAPACITY) {
            return
        }
        val grown = maxOf(samples.size * 2, INITIAL_SAMPLE_CAPACITY, requiredSize)
        samples = samples.copyOf(minOf(MAX_SAMPLE_CAPACITY, grown))
    }

    private fun createCaptureFile(): File {
        val dir = File(context.cacheDir, KEYBOARD_CAPTURE_CACHE_DIR).apply { mkdirs() }
        return File.createTempFile(DICTATION_CAPTURE_FILE_PREFIX, DICTATION_CAPTURE_FILE_SUFFIX, dir)
    }

    private fun writeFloatSamples(
        buffer: FloatArray,
        count: Int,
        scratch: ByteArray,
        gain: Float,
    ): Float {
        val output = sampleOutput ?: return 0f
        val byteCount = count * java.lang.Float.BYTES
        var byteIndex = 0
        var absSum = 0f
        for (index in 0 until count) {
            val boosted = AudioGain.boost(buffer[index], gain)
            absSum += abs(boosted)
            val bits = java.lang.Float.floatToIntBits(boosted)
            scratch[byteIndex] = (bits and BYTE_MASK).toByte()
            scratch[byteIndex + 1] = ((bits ushr Byte.SIZE_BITS) and BYTE_MASK).toByte()
            scratch[byteIndex + 2] = ((bits ushr (Byte.SIZE_BITS * 2)) and BYTE_MASK).toByte()
            scratch[byteIndex + 3] = ((bits ushr (Byte.SIZE_BITS * 3)) and BYTE_MASK).toByte()
            byteIndex += java.lang.Float.BYTES
        }
        try {
            output.write(scratch, 0, byteCount)
        } catch (firstFailure: Exception) {
            if (!firstFailure.isStorageExhaustion() || !reclaimEmergencyReserve()) throw firstFailure
            val trustedBytes = sampleCount.toLong() * java.lang.Float.BYTES
            val fileOutput = output as? FileOutputStream ?: throw firstFailure
            runCatching {
                fileOutput.channel.truncate(trustedBytes)
                fileOutput.channel.position(trustedBytes)
            }.getOrElse { throw firstFailure }
            Log.w(TAG, "Capture storage exhausted; reclaimed emergency reserve and retrying current block")
            output.write(scratch, 0, byteCount)
        }
        return absSum
    }

    private fun resetFileBackedCaptureLocked(deleteExisting: Boolean) {
        closeSampleOutputLocked()
        if (deleteExisting) {
            runCatching { sampleFile?.delete() }
        }
        sampleFile = null
    }

    private fun trimFileBackedCaptureToCountLocked() {
        val file = sampleFile ?: return
        val trustedBytes = sampleCount.toLong() * java.lang.Float.BYTES
        runCatching {
            if (file.length() != trustedBytes) {
                RandomAccessFile(file, "rw").use { it.setLength(trustedBytes) }
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to trim partial file-backed capture write", error)
        }
    }

    private fun closeSampleOutputLocked() =
        synchronized(captureWriteLock) {
            runCatching { sampleOutput?.flush() }
            runCatching { (sampleOutput as? FileOutputStream)?.fd?.sync() }
            runCatching { sampleOutput?.close() }
            sampleOutput = null
        }
}
