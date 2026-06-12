package dev.chirpboard.app.feature.recording.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import dev.chirpboard.app.core.audio.AudioGain
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.delay

class GaplessWavSegmentCapture(
    private val inputDeviceSelector: AudioInputDeviceSelector,
    private val sampleRate: Int,
    private val gainMultiplier: Float = 1f,
) : GaplessSegmentCaptureEngine {
    /** Serializes start/pause/stop/release control flows against each other. */
    private val controlLock = Any()

    /** Guards writer/segment state shared with the capture thread; never held across joins. */
    private val lock = Any()

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    @Volatile
    private var captureErrorListener: GaplessCaptureErrorListener? = null

    @Volatile
    private var silenceListener: GaplessSilenceListener? = null

    @Volatile
    private var pendingRotationTarget: File? = null

    @Volatile
    private var rotationLatch: CountDownLatch? = null

    @Volatile
    private var lastRotationCompletedFile: File? = null

    private var pcmReadBufferSize = DEFAULT_BUFFER_BYTES
    private var audioRecord: AudioRecord? = null
    private var writer: WavFileWriter? = null
    private var captureThread: Thread? = null
    private var currentSegmentFile: File? = null
    private val recentMaxAmplitude = AtomicInteger(0)

    override val maxAmplitude: Int
        get() = recentMaxAmplitude.get()

    override fun setCaptureErrorListener(listener: GaplessCaptureErrorListener?) {
        captureErrorListener = listener
    }

    override fun setSilenceListener(listener: GaplessSilenceListener?) {
        silenceListener = listener
    }

    override suspend fun start(segmentFile: File) {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        require(minBufferSize > 0) { "Invalid AudioRecord buffer size" }
        val bufferSize = minBufferSize * 2

        val record = buildInitializedAudioRecord(channelConfig, audioFormat, bufferSize)

        synchronized(controlLock) {
            synchronized(lock) {
                require(!running.get()) { "Capture already running" }
                segmentFile.parentFile?.mkdirs()
                currentSegmentFile = segmentFile
                lastRotationCompletedFile = null
                pcmReadBufferSize = bufferSize
                audioRecord = record
                openWriterLocked(segmentFile)
                record.startRecording()
                running.set(true)
                paused.set(false)
                startCaptureThreadLocked()
            }
        }
    }

    /**
     * Builds the AudioRecord, retrying transient init failures (common right after a
     * call ends) and releasing every failed instance so no native handle leaks.
     */
    private suspend fun buildInitializedAudioRecord(
        channelConfig: Int,
        audioFormat: Int,
        bufferSize: Int,
    ): AudioRecord {
        var lastFailure: Exception? = null
        repeat(INIT_MAX_ATTEMPTS) { attempt ->
            val record =
                try {
                    inputDeviceSelector.buildAudioRecord(
                        audioSource = MediaRecorder.AudioSource.MIC,
                        sampleRate = sampleRate,
                        channelConfig = channelConfig,
                        audioFormat = audioFormat,
                        bufferSize = bufferSize,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastFailure = e
                    null
                }
            if (record != null) {
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return record
                }
                runCatching { record.release() }
            }
            if (attempt < INIT_MAX_ATTEMPTS - 1) {
                delay(INIT_RETRY_DELAY_MS)
            }
        }
        throw lastFailure ?: IllegalStateException("AudioRecord failed to initialize after $INIT_MAX_ATTEMPTS attempts")
    }

    override fun rotateSegment(nextSegmentFile: File): SegmentRotationResult {
        synchronized(lock) {
            if (!running.get()) return SegmentRotationResult.Failed("Capture not running")
            if (pendingRotationTarget != null) return SegmentRotationResult.Failed("Segment rotation already pending")
        }

        val latch = CountDownLatch(1)
        synchronized(lock) {
            rotationLatch = latch
            pendingRotationTarget = nextSegmentFile
        }

        if (!latch.await(ROTATION_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            synchronized(lock) { cancelPendingRotationLocked() }
            return SegmentRotationResult.Failed("Timed out waiting for gapless segment rotation")
        }

        synchronized(lock) {
            val completed = lastRotationCompletedFile
            if (completed == null || !completed.exists()) {
                return SegmentRotationResult.Failed("Completed segment file missing after rotation")
            }
            if (currentSegmentFile != nextSegmentFile) {
                return SegmentRotationResult.Failed("Active segment path mismatch after rotation")
            }
            if (completed.length() < MIN_SEGMENT_BYTES) {
                return SegmentRotationResult.Failed("Completed segment too small after rotation")
            }
            return SegmentRotationResult.Success
        }
    }

    override fun cancelPendingRotation() {
        synchronized(lock) { cancelPendingRotationLocked() }
    }

    override fun pauseAndFinalizeSegment(): File? {
        synchronized(controlLock) {
            synchronized(lock) {
                cancelPendingRotationLocked()
                // Mirror stopAndFinalize: a cleared running flag alone is not proof the
                // segment is finalized — a racing failCapture clears it before closing the
                // writer. While the audio hardware is still held, fall through to join the
                // capture thread and (idempotently) finalize, so callers never commit a
                // segment whose header still carries placeholder sizes.
                if (!running.get() && audioRecord == null) return currentSegmentFile
                paused.set(true)
            }
            signalStopAndJoinCaptureThread()
            synchronized(lock) {
                finalizeWriterLocked()
                releaseAudioLocked()
                return currentSegmentFile
            }
        }
    }

    override suspend fun resume(nextSegmentFile: File) = start(nextSegmentFile)

    override fun stopAndFinalize(): File? {
        synchronized(controlLock) {
            synchronized(lock) {
                cancelPendingRotationLocked()
                if (!running.get() && audioRecord == null) return currentSegmentFile
            }
            signalStopAndJoinCaptureThread()
            synchronized(lock) {
                finalizeWriterLocked()
                releaseAudioLocked()
                return currentSegmentFile
            }
        }
    }

    override fun releaseWithoutSave() {
        synchronized(controlLock) {
            synchronized(lock) { cancelPendingRotationLocked() }
            signalStopAndJoinCaptureThread()
            synchronized(lock) {
                runCatching { writer?.close() }
                writer = null
                releaseAudioLocked()
                currentSegmentFile?.takeIf { it.exists() }?.delete()
                currentSegmentFile = null
            }
        }
    }

    override fun releaseAfterStopTimeout() {
        running.set(false)
        synchronized(lock) {
            cancelPendingRotationLocked()
            finalizeWriterLocked()
            releaseAudioLocked()
        }
    }

    /**
     * Clears the running flag and stops the AudioRecord (unblocking any blocking read so the
     * capture thread can hand off its final buffer), then joins OUTSIDE the state lock so the
     * capture thread can take it to finish the handoff.
     */
    private fun signalStopAndJoinCaptureThread() {
        val thread =
            synchronized(lock) {
                running.set(false)
                runCatching { audioRecord?.stop() }
                captureThread.also { captureThread = null }
            }
        thread?.takeIf { it !== Thread.currentThread() }?.join(CAPTURE_JOIN_TIMEOUT_MS)
    }

    private fun cancelPendingRotationLocked() {
        pendingRotationTarget = null
        rotationLatch?.countDown()
        rotationLatch = null
    }

    private fun openWriterLocked(segmentFile: File) {
        writer?.close()
        writer = WavFileWriter(segmentFile, sampleRate)
    }

    private fun finalizeWriterLocked() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun startCaptureThreadLocked() {
        val bufferSize = pcmReadBufferSize
        captureThread =
            Thread(
                { runCaptureLoop(ByteArray(bufferSize)) },
                "gapless-wav-capture",
            ).also { it.start() }
    }

    /**
     * Capture-thread entry point. The whole loop is exception-guarded (ERR-12): a write
     * failure (ENOSPC mid-write, segment-file creation failure during rotation) must take
     * the same stop-with-save path as a dead AudioRecord — never unwind the raw thread
     * into the default handler and kill the shared app+IME process.
     */
    private fun runCaptureLoop(buffer: ByteArray) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runCaptureLoopInner(buffer)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failCapture("WAV capture thread interrupted", audioRecordErrorCode = null)
        } catch (e: Exception) {
            failCapture("WAV capture failed: ${e.message}", audioRecordErrorCode = null)
        }
    }

    private fun runCaptureLoopInner(buffer: ByteArray) {
        var routingChecked = false
        var silentBytesRun = 0L
        var silenceNotified = false
        while (true) {
            if (paused.get()) {
                if (!running.get()) {
                    // Mirror the stop path: hand off audio still buffered in the HAL so a
                    // pause boundary never clips the last word that a stop would keep.
                    audioRecord?.let { record -> drainRemainingAudio(record, buffer) }
                    return
                }
                Thread.sleep(PAUSE_POLL_MS)
                continue
            }
            val record = audioRecord ?: return
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read < 0) {
                failCapture("AudioRecord.read returned $read during WAV capture", read)
                return
            }
            if (read > 0) {
                if (!routingChecked) {
                    routingChecked = true
                    runCatching { inputDeviceSelector.refreshActiveDeviceFromRouting(record) }
                }
                val peak = processBuffer(buffer, read)
                synchronized(lock) {
                    writer?.appendPcm16(buffer, read)
                    maybeRotateLocked()
                }
                silentBytesRun = if (peak == 0) silentBytesRun + read else 0L
                val nowSilenced = silentBytesRun >= silenceWarningBytes
                if (nowSilenced != silenceNotified) {
                    silenceNotified = nowSilenced
                    silenceListener?.onSilenceStateChanged(nowSilenced)
                }
            }
            if (!running.get()) {
                drainRemainingAudio(record, buffer)
                return
            }
        }
    }

    /**
     * Applies the configured input gain (soft-limited, no-op at 1.0x) in place, then
     * returns the post-gain peak amplitude for visualization and silence detection.
     */
    private fun processBuffer(
        buffer: ByteArray,
        size: Int,
    ): Int {
        AudioGain.applyGainPcm16(buffer, size, gainMultiplier)
        return updateAmplitude(buffer, size)
    }

    /** After stop is signaled, hand off whatever audio is still buffered before exiting. */
    private fun drainRemainingAudio(
        record: AudioRecord,
        buffer: ByteArray,
    ) {
        repeat(FINAL_DRAIN_READS) {
            val read =
                runCatching {
                    record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                }.getOrDefault(-1)
            if (read <= 0) return
            processBuffer(buffer, read)
            synchronized(lock) { writer?.appendPcm16(buffer, read) }
        }
    }

    /**
     * Error exit for the capture loop: finalizes the partial segment, releases the audio
     * hardware, then notifies the listener (only when no stop was already requested) with
     * no locks held.
     */
    private fun failCapture(
        message: String,
        audioRecordErrorCode: Int?,
    ) {
        val notifyListener = running.compareAndSet(true, false)
        synchronized(lock) {
            cancelPendingRotationLocked()
            finalizeWriterLocked()
            releaseAudioLocked()
        }
        if (notifyListener) {
            captureErrorListener?.onCaptureError(
                GaplessCaptureError(
                    message = message,
                    audioRecordErrorCode = audioRecordErrorCode,
                ),
            )
        }
    }

    private fun maybeRotateLocked() {
        val next = pendingRotationTarget ?: return
        pendingRotationTarget = null
        finalizeWriterLocked()
        next.parentFile?.mkdirs()
        openWriterLocked(next)
        lastRotationCompletedFile = currentSegmentFile
        currentSegmentFile = next
        rotationLatch?.countDown()
        rotationLatch = null
    }

    private fun releaseAudioLocked() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    private fun updateAmplitude(
        buffer: ByteArray,
        size: Int,
    ): Int {
        var peak = 0
        var index = 0
        while (index + 1 < size) {
            val sample = (buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            peak = maxOf(peak, abs(signed))
            index += 2
        }
        recentMaxAmplitude.set(peak)
        return peak
    }

    /** Sustained all-zero PCM bytes before the silence listener reports silence. */
    private val silenceWarningBytes: Long = sampleRate.toLong() * PCM16_BYTES_PER_SAMPLE * SILENCE_WARNING_SECONDS

    companion object {
        private const val CAPTURE_JOIN_TIMEOUT_MS = 5_000L
        private const val ROTATION_WAIT_TIMEOUT_MS = 5_000L
        private const val DEFAULT_BUFFER_BYTES = 4096
        private const val PAUSE_POLL_MS = 20L
        private const val FINAL_DRAIN_READS = 8
        private const val MIN_SEGMENT_BYTES = RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES
        private const val INIT_MAX_ATTEMPTS = 3
        private const val INIT_RETRY_DELAY_MS = 150L
        private const val PCM16_BYTES_PER_SAMPLE = 2L
        private const val SILENCE_WARNING_SECONDS = 4L
    }
}
