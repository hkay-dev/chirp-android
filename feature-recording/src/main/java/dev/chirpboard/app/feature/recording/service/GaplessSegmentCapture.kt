package dev.chirpboard.app.feature.recording.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Process
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Continuous AudioRecord → AAC → M4A capture with gapless segment rotation.
 */
class GaplessAacSegmentCapture(
    private val inputDeviceSelector: AudioInputDeviceSelector,
    private val sampleRate: Int,
    private val bitRate: Int,
) : GaplessSegmentCaptureEngine {
    /** Serializes start/pause/stop/release control flows against each other. */
    private val controlLock = Any()

    /** Guards codec/muxer/segment state shared with the capture thread; never held across joins. */
    private val lock = Any()

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    @Volatile
    private var captureErrorListener: GaplessCaptureErrorListener? = null

    @Volatile
    private var pendingRotationTarget: File? = null

    @Volatile
    private var rotationLatch: CountDownLatch? = null

    @Volatile
    private var lastRotationCompletedFile: File? = null

    private var pcmReadBufferSize = DEFAULT_BUFFER_BYTES
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    private var muxerStarted = false
    private var codecOutputFormat: MediaFormat? = null

    private var captureThread: Thread? = null
    private var currentSegmentFile: File? = null
    private var segmentStartPtsUs = 0L
    private var lastWrittenCodecPtsUs = 0L
    private var totalPcmBytesQueued = 0L

    private val recentMaxAmplitude = AtomicInteger(0)

    override val maxAmplitude: Int
        get() = recentMaxAmplitude.get()

    override fun setCaptureErrorListener(listener: GaplessCaptureErrorListener?) {
        captureErrorListener = listener
    }

    override suspend fun start(segmentFile: File) {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        require(minBufferSize > 0) { "Invalid AudioRecord buffer size" }
        val bufferSize = minBufferSize * 2

        val record =
            inputDeviceSelector.buildAudioRecord(
                audioSource = MediaRecorder.AudioSource.MIC,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                bufferSize = bufferSize,
            )
        require(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }

        val encoder = createEncoder(bufferSize)

        synchronized(controlLock) {
            synchronized(lock) {
                require(!running.get()) { "Capture already running" }
                codecOutputFormat = null
                segmentFile.parentFile?.mkdirs()
                currentSegmentFile = segmentFile
                lastRotationCompletedFile = null
                segmentStartPtsUs = 0L
                lastWrittenCodecPtsUs = 0L
                totalPcmBytesQueued = 0L
                pcmReadBufferSize = bufferSize
                audioRecord = record
                codec = encoder
                openMuxerLocked(segmentFile)
                record.startRecording()
                running.set(true)
                paused.set(false)
                startCaptureThreadLocked()
            }
        }
    }

    /** Rotate to [nextSegmentFile] without stopping the mic; blocks until the swap completes. */
    override fun rotateSegment(nextSegmentFile: File): SegmentRotationResult {
        synchronized(lock) {
            if (!running.get()) {
                return SegmentRotationResult.Failed("Capture not running")
            }
            if (pendingRotationTarget != null) {
                return SegmentRotationResult.Failed("Segment rotation already pending")
            }
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
        synchronized(lock) {
            cancelPendingRotationLocked()
        }
    }

    /** Finalize the active segment and stop capture hardware (used on user pause). */
    override fun pauseAndFinalizeSegment(): File? {
        synchronized(controlLock) {
            synchronized(lock) {
                cancelPendingRotationLocked()
                // Mirror stopAndFinalize: a cleared running flag alone is not proof the
                // segment is finalized — a racing failCapture clears it before draining the
                // encoder and stopping the muxer. While the audio hardware is still held,
                // fall through to join the capture thread and (idempotently) finalize, so
                // callers never commit a segment whose muxer was not finalized.
                if (!running.get() && audioRecord == null) return currentSegmentFile
                paused.set(true)
            }
            signalStopAndJoinCaptureThread()
            synchronized(lock) {
                drainEncoderLocked(endOfStream = true)
                finalizeMuxerLocked()
                releaseAudioLocked()
                return currentSegmentFile
            }
        }
    }

    /** Start a new segment after [pauseAndFinalizeSegment]. */
    override suspend fun resume(nextSegmentFile: File) = start(nextSegmentFile)

    override fun stopAndFinalize(): File? {
        synchronized(controlLock) {
            synchronized(lock) {
                cancelPendingRotationLocked()
                if (!running.get() && audioRecord == null) {
                    return currentSegmentFile
                }
            }
            signalStopAndJoinCaptureThread()
            synchronized(lock) {
                drainEncoderLocked(endOfStream = true)
                finalizeMuxerLocked()
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
                releaseAudioLocked()
                runCatching {
                    if (muxerStarted) muxer?.stop()
                }
                runCatching { muxer?.release() }
                muxer = null
                muxerStarted = false
                currentSegmentFile?.takeIf { it.exists() }?.delete()
                currentSegmentFile = null
            }
        }
    }

    override fun releaseAfterStopTimeout() {
        running.set(false)
        synchronized(lock) {
            cancelPendingRotationLocked()
            runCatching { drainEncoderLocked(endOfStream = true) }
            finalizeMuxerLocked()
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

    private fun createEncoder(bufferSize: Int): MediaCodec {
        val format =
            MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        return encoder
    }

    private fun startCaptureThreadLocked() {
        val bufferSize = pcmReadBufferSize
        captureThread =
            Thread(
                { runCaptureLoop(ByteArray(bufferSize)) },
                "gapless-segment-capture",
            ).also { it.start() }
    }

    private fun runCaptureLoop(buffer: ByteArray) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (true) {
            if (paused.get()) {
                if (!running.get()) return
                Thread.sleep(PAUSE_POLL_MS)
                continue
            }
            val record = audioRecord ?: return
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read < 0) {
                failCapture(read)
                return
            }
            if (read > 0) {
                updateAmplitude(buffer, read)
                synchronized(lock) {
                    queuePcmLocked(buffer, read)
                    maybeRotateLocked()
                }
            }
            if (!running.get()) {
                drainRemainingAudio(record, buffer)
                return
            }
        }
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
            updateAmplitude(buffer, read)
            synchronized(lock) { queuePcmLocked(buffer, read) }
        }
    }

    /**
     * Error exit for the capture loop: finalizes the partial segment, releases the audio
     * hardware, then notifies the listener (only when no stop was already requested) with
     * no locks held.
     */
    private fun failCapture(readResult: Int) {
        val notifyListener = running.compareAndSet(true, false)
        synchronized(lock) {
            cancelPendingRotationLocked()
            runCatching { drainEncoderLocked(endOfStream = true) }
            finalizeMuxerLocked()
            releaseAudioLocked()
        }
        if (notifyListener) {
            captureErrorListener?.onCaptureError(
                GaplessCaptureError(
                    message = "AudioRecord.read returned $readResult during AAC capture",
                    audioRecordErrorCode = readResult,
                ),
            )
        }
    }

    private fun maybeRotateLocked() {
        val next = pendingRotationTarget ?: return
        pendingRotationTarget = null
        val completed = currentSegmentFile
        flushEncoderOutputLocked()
        finalizeMuxerLocked()
        next.parentFile?.mkdirs()
        openMuxerLocked(next)
        segmentStartPtsUs = lastWrittenCodecPtsUs
        lastRotationCompletedFile = completed
        currentSegmentFile = next
        rotationLatch?.countDown()
        rotationLatch = null
    }

    private fun queuePcmLocked(
        buffer: ByteArray,
        size: Int,
    ) {
        var offset = 0
        while (offset < size) {
            val encoder = codec ?: return
            var inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            while (inputIndex < 0) {
                drainEncoderLocked(endOfStream = false)
                inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            }

            val inputBuffer = encoder.getInputBuffer(inputIndex) ?: return
            val chunkSize = minOf(size - offset, inputBuffer.capacity())
            inputBuffer.clear()
            inputBuffer.put(buffer, offset, chunkSize)

            val presentationTimeUs = (totalPcmBytesQueued * 1_000_000L) / (sampleRate * BYTES_PER_PCM_SAMPLE)
            encoder.queueInputBuffer(inputIndex, 0, chunkSize, presentationTimeUs, 0)
            totalPcmBytesQueued += chunkSize
            offset += chunkSize
            drainEncoderLocked(endOfStream = false)
        }
    }

    private fun flushEncoderOutputLocked() {
        repeat(ENCODER_FLUSH_PASSES) {
            drainEncoderLocked(endOfStream = false)
        }
    }

    private fun drainEncoderLocked(endOfStream: Boolean) {
        val encoder = codec ?: return
        if (endOfStream) {
            val inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    (totalPcmBytesQueued * 1_000_000L) / (sampleRate * BYTES_PER_PCM_SAMPLE),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    codecOutputFormat = encoder.outputFormat
                    if (!muxerStarted) {
                        val mux = muxer ?: return
                        muxerTrackIndex = mux.addTrack(codecOutputFormat!!)
                        mux.start()
                        muxerStarted = true
                    }
                }

                outputIndex >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: continue
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        lastWrittenCodecPtsUs = maxOf(lastWrittenCodecPtsUs, bufferInfo.presentationTimeUs)
                        bufferInfo.presentationTimeUs =
                            (bufferInfo.presentationTimeUs - segmentStartPtsUs).coerceAtLeast(0L)
                        muxer?.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }

                else -> return
            }
        }
    }

    private fun openMuxerLocked(segmentFile: File) {
        muxer = MediaMuxer(segmentFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerTrackIndex = -1
        muxerStarted = false
        codecOutputFormat?.let { format ->
            muxerTrackIndex = muxer!!.addTrack(format)
            muxer!!.start()
            muxerStarted = true
        }
    }

    private fun finalizeMuxerLocked() {
        flushEncoderOutputLocked()
        runCatching {
            if (muxerStarted) muxer?.stop()
        }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
    }

    private fun releaseAudioLocked() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun updateAmplitude(
        buffer: ByteArray,
        size: Int,
    ) {
        var peak = 0
        var index = 0
        while (index + 1 < size) {
            val sample = (buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            peak = maxOf(peak, abs(signed))
            index += 2
        }
        recentMaxAmplitude.set(peak)
    }

    companion object {
        private const val TAG = "GaplessAacSegmentCapture"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val CAPTURE_JOIN_TIMEOUT_MS = 5_000L
        private const val ROTATION_WAIT_TIMEOUT_MS = 5_000L
        private const val BYTES_PER_PCM_SAMPLE = 2
        private const val DEFAULT_BUFFER_BYTES = 4096
        private const val PAUSE_POLL_MS = 20L
        private const val FINAL_DRAIN_READS = 8
        private const val ENCODER_FLUSH_PASSES = 8
        private const val MIN_SEGMENT_BYTES = RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES
    }
}
