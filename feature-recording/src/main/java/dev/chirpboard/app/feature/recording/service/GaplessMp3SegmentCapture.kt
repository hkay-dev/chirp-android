package dev.chirpboard.app.feature.recording.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class GaplessMp3SegmentCapture(
    private val inputDeviceSelector: AudioInputDeviceSelector,
    private val sampleRate: Int,
    private val bitRate: Int,
) : GaplessSegmentCaptureEngine {
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    @Volatile
    private var pendingRotationTarget: File? = null

    @Volatile
    private var rotationLatch: CountDownLatch? = null

    @Volatile
    private var lastRotationCompletedFile: File? = null

    private var pcmReadBufferSize = DEFAULT_BUFFER_BYTES
    private var audioRecord: AudioRecord? = null
    private var encoder: AndroidLame? = null
    private var outputStream: BufferedOutputStream? = null
    private var captureThread: Thread? = null
    private var currentSegmentFile: File? = null
    private val recentMaxAmplitude = AtomicInteger(0)

    override val maxAmplitude: Int
        get() = recentMaxAmplitude.get()

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

        synchronized(lock) {
            require(!running.get()) { "Capture already running" }
            segmentFile.parentFile?.mkdirs()
            currentSegmentFile = segmentFile
            lastRotationCompletedFile = null
            pcmReadBufferSize = bufferSize
            audioRecord = record
            openEncoderLocked(segmentFile)
            record.startRecording()
            running.set(true)
            paused.set(false)
            startCaptureThreadLocked()
        }
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
            cancelPendingRotationLocked()
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
        synchronized(lock) {
            cancelPendingRotationLocked()
            if (!running.get()) return currentSegmentFile
            paused.set(true)
            running.set(false)
            captureThread?.join(CAPTURE_JOIN_TIMEOUT_MS)
            captureThread = null
            flushEncoderLocked()
            closeEncoderLocked()
            releaseAudioLocked()
            return currentSegmentFile
        }
    }

    override suspend fun resume(nextSegmentFile: File) = start(nextSegmentFile)

    override fun stopAndFinalize(): File? {
        synchronized(lock) {
            cancelPendingRotationLocked()
            if (!running.get() && audioRecord == null) return currentSegmentFile
            running.set(false)
            captureThread?.join(CAPTURE_JOIN_TIMEOUT_MS)
            captureThread = null
            flushEncoderLocked()
            closeEncoderLocked()
            releaseAudioLocked()
            return currentSegmentFile
        }
    }

    override fun releaseWithoutSave() {
        synchronized(lock) {
            cancelPendingRotationLocked()
            running.set(false)
            captureThread?.join(CAPTURE_JOIN_TIMEOUT_MS)
            captureThread = null
            closeEncoderLocked()
            releaseAudioLocked()
            currentSegmentFile?.takeIf { it.exists() }?.delete()
            currentSegmentFile = null
        }
    }

    private fun cancelPendingRotationLocked() {
        pendingRotationTarget = null
        rotationLatch?.countDown()
        rotationLatch = null
    }

    private fun openEncoderLocked(segmentFile: File) {
        closeEncoderLocked()
        encoder =
            LameBuilder()
                .setInSampleRate(sampleRate)
                .setOutChannels(1)
                .setOutBitrate(bitRate / 1000)
                .setOutSampleRate(sampleRate)
                .build()
        outputStream = BufferedOutputStream(FileOutputStream(segmentFile))
    }

    private fun flushEncoderLocked() {
        val lame = encoder ?: return
        val mp3Buffer = ByteArray(MP3_BUFFER_SIZE)
        val flushSize = lame.flush(mp3Buffer)
        if (flushSize > 0) {
            outputStream?.write(mp3Buffer, 0, flushSize)
        }
        outputStream?.flush()
    }

    private fun closeEncoderLocked() {
        runCatching { outputStream?.close() }
        outputStream = null
        encoder = null
    }

    private fun encodePcmLocked(
        buffer: ByteArray,
        size: Int,
    ) {
        val lame = encoder ?: return
        val stream = outputStream ?: return
        val sampleCount = size / 2
        if (sampleCount <= 0) return

        val shorts = ShortArray(sampleCount)
        val byteBuffer = ByteBuffer.wrap(buffer, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until sampleCount) {
            shorts[index] = byteBuffer.short
        }

        val mp3Buffer = ByteArray(MP3_BUFFER_SIZE)
        val encodedSize = lame.encode(shorts, shorts, sampleCount, mp3Buffer)
        if (encodedSize > 0) {
            stream.write(mp3Buffer, 0, encodedSize)
        }
    }

    private fun startCaptureThreadLocked() {
        val bufferSize = pcmReadBufferSize
        captureThread =
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    val buffer = ByteArray(bufferSize)
                    while (running.get()) {
                        if (paused.get()) {
                            Thread.sleep(20)
                            continue
                        }
                        val record = audioRecord ?: break
                        val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (read == 0) continue
                        if (read < 0) {
                            running.set(false)
                            break
                        }
                        updateAmplitude(buffer, read)
                        val keepRunning =
                            synchronized(lock) {
                                if (!running.get()) {
                                    false
                                } else {
                                    encodePcmLocked(buffer, read)
                                    maybeRotateLocked()
                                    true
                                }
                            }
                        if (!keepRunning) break
                    }
                },
                "gapless-mp3-capture",
            ).also { it.start() }
    }

    private fun maybeRotateLocked() {
        val next = pendingRotationTarget ?: return
        pendingRotationTarget = null
        flushEncoderLocked()
        closeEncoderLocked()
        next.parentFile?.mkdirs()
        openEncoderLocked(next)
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
        private const val CAPTURE_JOIN_TIMEOUT_MS = 5_000L
        private const val ROTATION_WAIT_TIMEOUT_MS = 5_000L
        private const val DEFAULT_BUFFER_BYTES = 4096
        private const val MP3_BUFFER_SIZE = 8192
        private const val MIN_SEGMENT_BYTES = RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES
    }
}
