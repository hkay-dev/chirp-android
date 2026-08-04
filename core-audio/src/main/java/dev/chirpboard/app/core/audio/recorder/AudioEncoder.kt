package dev.chirpboard.app.core.audio.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import dev.chirpboard.app.core.audio.KeyboardRecordingQualityConfig
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.WavFileWriter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/**
 * Encodes raw PCM audio samples to the configured recording output format.
 *
 * WAV output uses direct PCM container writes ([WavFileWriter]) rather than MediaCodec.
 * Transcription should decode WAV via direct PCM read before attempting MediaCodec.
 */
class AudioEncoder
    @Inject
    constructor() {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val DEFAULT_BIT_RATE = 64_000
        private const val TIMEOUT_US = 10_000L
        private const val MP3_BUFFER_SIZE = 8192
        private const val STREAM_CHUNK_SAMPLES = 4096
    }

    fun encode(
        samples: FloatArray,
        sampleRate: Int,
        outputPath: String,
        format: RecordingOutputFormat,
        config: KeyboardRecordingQualityConfig = KeyboardRecordingQualityConfig(DEFAULT_BIT_RATE),
    ): Boolean =
        when (format) {
            RecordingOutputFormat.M4A -> encodeToM4a(samples, sampleRate, outputPath, config)
            RecordingOutputFormat.WAV -> encodeToWav(samples, sampleRate, outputPath)
            RecordingOutputFormat.MP3 -> encodeToMp3(samples, sampleRate, outputPath, config)
        }

    fun encodePcmFloatFile(
        inputPath: String,
        sampleCount: Long,
        sampleRate: Int,
        outputPath: String,
        format: RecordingOutputFormat,
        config: KeyboardRecordingQualityConfig = KeyboardRecordingQualityConfig(DEFAULT_BIT_RATE),
    ): Boolean {
        if (sampleCount <= 0L) {
            Log.w(TAG, "Cannot encode empty PCM file")
            return false
        }
        return when (format) {
            RecordingOutputFormat.M4A -> encodePcmFloatFileToM4a(inputPath, sampleCount, sampleRate, outputPath, config)
            RecordingOutputFormat.WAV -> encodePcmFloatFileToWav(inputPath, sampleCount, sampleRate, outputPath)
            RecordingOutputFormat.MP3 -> encodePcmFloatFileToMp3(inputPath, sampleCount, sampleRate, outputPath, config)
        }
    }

    fun encodePcm16WavFile(
        inputPath: String,
        outputPath: String,
        format: RecordingOutputFormat,
        config: KeyboardRecordingQualityConfig = KeyboardRecordingQualityConfig(DEFAULT_BIT_RATE),
    ): Boolean {
        val inputFile = File(inputPath)
        if (!WavFileWriter.hasValidHeader(inputFile)) {
            Log.w(TAG, "Cannot encode invalid WAV file: $inputPath")
            return false
        }

        return when (format) {
            RecordingOutputFormat.WAV ->
                runCatching {
                    File(outputPath).parentFile?.mkdirs()
                    inputFile.copyTo(File(outputPath), overwrite = true)
                    true
                }.getOrElse { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.e(TAG, "WAV copy failed", error)
                    runCatching { File(outputPath).delete() }
                    false
                }
            RecordingOutputFormat.M4A -> encodePcm16WavFileToM4a(inputPath, outputPath, config)
            RecordingOutputFormat.MP3 -> encodePcm16WavFileToMp3(inputPath, outputPath, config)
        }
    }

    /** @deprecated Use [encode] with an explicit [RecordingOutputFormat]. */
    fun encodeToM4a(
        samples: FloatArray,
        sampleRate: Int,
        outputPath: String,
        config: KeyboardRecordingQualityConfig = KeyboardRecordingQualityConfig(DEFAULT_BIT_RATE),
    ): Boolean {
        if (samples.isEmpty()) {
            Log.w(TAG, "Cannot encode empty samples")
            return false
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerSession: MuxerSession? = null

        return try {
            File(outputPath).parentFile?.mkdirs()

            val pcmData = floatToPcm16(samples)
            val mediaFormat = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                // Chunk-sized like the streaming variants: asking for input buffers sized
                // to the whole capture (tens of MB for long sessions) makes many codecs
                // reject configure() or allocate huge buffers. The feed loop below already
                // chunks by inputBuffer.capacity().
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, STREAM_CHUNK_SAMPLES * 2)
            }

            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val mux = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val session = MuxerSession(mux)
            muxerSession = session

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var inputOffset = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val remaining = pcmData.size - inputOffset

                        if (remaining <= 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = minOf(remaining, inputBuffer.capacity())
                            inputBuffer.clear()
                            inputBuffer.put(pcmData, inputOffset, size)
                            val presentationTimeUs = (inputOffset.toLong() * 1_000_000) / (sampleRate * 2)
                            codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                            inputOffset += size
                        }
                    }
                }

                outputDone = drainEncoderOutput(codec, bufferInfo, session, waitForEndOfStream = inputDone)
            }

            Log.d(TAG, "Successfully encoded to $outputPath")
            return true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Encoding failed", e)
            runCatching { File(outputPath).delete() }
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerSession?.started == true) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun encodeToWav(
        samples: FloatArray,
        sampleRate: Int,
        outputPath: String,
    ): Boolean {
        if (samples.isEmpty()) return false

        return runCatching {
            File(outputPath).parentFile?.mkdirs()
            WavFileWriter(File(outputPath), sampleRate).use { writer ->
                val pcm = WavFileWriter.floatToPcm16(samples)
                writer.appendPcm16(pcm, pcm.size)
            }
            true
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "WAV encoding failed", error)
            runCatching { File(outputPath).delete() }
            false
        }
    }

    private fun encodePcmFloatFileToWav(
        inputPath: String,
        sampleCount: Long,
        sampleRate: Int,
        outputPath: String,
    ): Boolean =
        runCatching {
            File(outputPath).parentFile?.mkdirs()
            WavFileWriter(File(outputPath), sampleRate).use { writer ->
                forEachFloatChunk(inputPath, sampleCount) { chunk ->
                    val pcm = floatToPcm16(chunk)
                    writer.appendPcm16(pcm, pcm.size)
                }
            }
            true
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "Streaming WAV encoding failed", error)
            runCatching { File(outputPath).delete() }
            false
        }

    private fun encodeToMp3(
        samples: FloatArray,
        sampleRate: Int,
        outputPath: String,
        config: KeyboardRecordingQualityConfig,
    ): Boolean {
        if (samples.isEmpty()) return false

        return runCatching {
            File(outputPath).parentFile?.mkdirs()
            val pcm = floatToPcm16(samples)
            val lame =
                LameBuilder()
                    .setInSampleRate(sampleRate)
                    .setOutChannels(1)
                    .setOutBitrate(config.bitRate / 1000)
                    .setOutSampleRate(sampleRate)
                    .build()
            val mp3Buffer = ByteArray(MP3_BUFFER_SIZE)
            BufferedOutputStream(FileOutputStream(outputPath)).use { output ->
                var offset = 0
                while (offset < pcm.size) {
                    val chunkBytes = minOf(pcm.size - offset, MP3_BUFFER_SIZE)
                    val sampleCount = chunkBytes / 2
                    val shorts = ShortArray(sampleCount)
                    val byteBuffer = ByteBuffer.wrap(pcm, offset, chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (index in 0 until sampleCount) {
                        shorts[index] = byteBuffer.short
                    }
                    val encodedSize = lame.encode(shorts, shorts, sampleCount, mp3Buffer)
                    if (encodedSize > 0) {
                        output.write(mp3Buffer, 0, encodedSize)
                    }
                    offset += chunkBytes
                }
                val flushSize = lame.flush(mp3Buffer)
                if (flushSize > 0) {
                    output.write(mp3Buffer, 0, flushSize)
                }
            }
            true
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "MP3 encoding failed", error)
            runCatching { File(outputPath).delete() }
            false
        }
    }

    private fun encodePcmFloatFileToMp3(
        inputPath: String,
        sampleCount: Long,
        sampleRate: Int,
        outputPath: String,
        config: KeyboardRecordingQualityConfig,
    ): Boolean =
        runCatching {
            File(outputPath).parentFile?.mkdirs()
            val lame =
                LameBuilder()
                    .setInSampleRate(sampleRate)
                    .setOutChannels(1)
                    .setOutBitrate(config.bitRate / 1000)
                    .setOutSampleRate(sampleRate)
                    .build()
            val mp3Buffer = ByteArray(MP3_BUFFER_SIZE)
            BufferedOutputStream(FileOutputStream(outputPath)).use { output ->
                forEachFloatChunk(inputPath, sampleCount) { chunk ->
                    val shorts = floatToShorts(chunk)
                    val encodedSize = lame.encode(shorts, shorts, shorts.size, mp3Buffer)
                    if (encodedSize > 0) {
                        output.write(mp3Buffer, 0, encodedSize)
                    }
                }
                val flushSize = lame.flush(mp3Buffer)
                if (flushSize > 0) {
                    output.write(mp3Buffer, 0, flushSize)
                }
            }
            true
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "Streaming MP3 encoding failed", error)
            runCatching { File(outputPath).delete() }
            false
        }

    private fun encodePcmFloatFileToM4a(
        inputPath: String,
        sampleCount: Long,
        sampleRate: Int,
        outputPath: String,
        config: KeyboardRecordingQualityConfig,
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerSession: MuxerSession? = null
        var reader: PcmFloatFileReader? = null

        return try {
            File(outputPath).parentFile?.mkdirs()
            val mediaFormat = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, STREAM_CHUNK_SAMPLES * 2)
            }

            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val mux = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val session = MuxerSession(mux)
            muxerSession = session
            reader = PcmFloatFileReader(inputPath, sampleCount)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var inputBytePosition = 0L
            var currentPcm = ByteArray(0)
            var currentOffset = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        if (currentOffset >= currentPcm.size) {
                            currentPcm = reader.readPcm16Chunk() ?: ByteArray(0)
                            currentOffset = 0
                        }

                        if (currentPcm.isEmpty()) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = minOf(currentPcm.size - currentOffset, inputBuffer.capacity())
                            inputBuffer.clear()
                            inputBuffer.put(currentPcm, currentOffset, size)
                            val presentationTimeUs = (inputBytePosition * 1_000_000) / (sampleRate * 2)
                            codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                            inputBytePosition += size
                            currentOffset += size
                        }
                    }
                }

                outputDone = drainEncoderOutput(codec, bufferInfo, session, waitForEndOfStream = inputDone)
            }

            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Streaming M4A encoding failed", e)
            runCatching { File(outputPath).delete() }
            false
        } finally {
            runCatching { reader?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerSession?.started == true) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun encodePcm16WavFileToM4a(
        inputPath: String,
        outputPath: String,
        config: KeyboardRecordingQualityConfig,
    ): Boolean {
        val sampleRate = readWavSampleRate(File(inputPath))
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerSession: MuxerSession? = null

        return try {
            File(outputPath).parentFile?.mkdirs()
            val mediaFormat = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, STREAM_CHUNK_SAMPLES * 2)
            }

            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mux = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val session = MuxerSession(mux)
            muxerSession = session

            FileInputStream(inputPath).use { input ->
                input.skip(WavFileWriter.WAV_HEADER_BYTES.toLong())
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var inputBytePosition = 0L
                val sourceBuffer = ByteArray(STREAM_CHUNK_SAMPLES * 2)
                var pending = ByteArray(0)
                var pendingOffset = 0

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)!!
                            if (pendingOffset >= pending.size) {
                                val read = input.read(sourceBuffer)
                                pending =
                                    if (read > 0) {
                                        sourceBuffer.copyOf(read)
                                    } else {
                                        ByteArray(0)
                                    }
                                pendingOffset = 0
                            }

                            if (pending.isEmpty()) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val size = minOf(pending.size - pendingOffset, inputBuffer.capacity())
                                inputBuffer.clear()
                                inputBuffer.put(pending, pendingOffset, size)
                                val presentationTimeUs = (inputBytePosition * 1_000_000) / (sampleRate * 2)
                                codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                                inputBytePosition += size
                                pendingOffset += size
                            }
                        }
                    }

                    outputDone = drainEncoderOutput(codec, bufferInfo, session, waitForEndOfStream = inputDone)
                }
            }
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "WAV to M4A encoding failed", e)
            runCatching { File(outputPath).delete() }
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerSession?.started == true) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun encodePcm16WavFileToMp3(
        inputPath: String,
        outputPath: String,
        config: KeyboardRecordingQualityConfig,
    ): Boolean {
        val sampleRate = readWavSampleRate(File(inputPath))
        return runCatching {
            File(outputPath).parentFile?.mkdirs()
            val lame =
                LameBuilder()
                    .setInSampleRate(sampleRate)
                    .setOutChannels(1)
                    .setOutBitrate(config.bitRate / 1000)
                    .setOutSampleRate(sampleRate)
                    .build()
            val pcmBuffer = ByteArray(MP3_BUFFER_SIZE)
            val mp3Buffer = ByteArray(MP3_BUFFER_SIZE)
            BufferedOutputStream(FileOutputStream(outputPath)).use { output ->
                FileInputStream(inputPath).use { input ->
                    input.skip(WavFileWriter.WAV_HEADER_BYTES.toLong())
                    while (true) {
                        val read = input.read(pcmBuffer)
                        if (read <= 0) break
                        val sampleCount = read / 2
                        if (sampleCount == 0) continue
                        val shorts = ShortArray(sampleCount)
                        val byteBuffer = ByteBuffer.wrap(pcmBuffer, 0, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (index in 0 until sampleCount) {
                            shorts[index] = byteBuffer.short
                        }
                        val encodedSize = lame.encode(shorts, shorts, sampleCount, mp3Buffer)
                        if (encodedSize > 0) {
                            output.write(mp3Buffer, 0, encodedSize)
                        }
                    }
                }
                val flushSize = lame.flush(mp3Buffer)
                if (flushSize > 0) {
                    output.write(mp3Buffer, 0, flushSize)
                }
            }
            true
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "WAV to MP3 encoding failed", error)
            runCatching { File(outputPath).delete() }
            false
        }
    }

    /**
     * Drains every output buffer the encoder currently has ready into the muxer,
     * returning true once the end-of-stream buffer has been consumed.
     *
     * Draining at most one buffer per feed iteration (the previous loop shape) lets the
     * encoder's output queue fill, after which every ~64ms AAC frame costs a [TIMEOUT_US]
     * dequeueInputBuffer stall — multi-second finalizes for sub-minute captures. While
     * input is still flowing this polls with zero timeout and returns as soon as the codec
     * has nothing ready; once [waitForEndOfStream] is set (end-of-stream already queued on
     * the input side) it keeps [TIMEOUT_US]-polling until the end-of-stream buffer surfaces.
     */
    private fun drainEncoderOutput(
        codec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        session: MuxerSession,
        waitForEndOfStream: Boolean,
    ): Boolean {
        val timeoutUs = if (waitForEndOfStream) TIMEOUT_US else 0L
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEndOfStream) return false
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    session.onOutputFormatChanged(codec.outputFormat)
                }

                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && session.started) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        session.writeSample(outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return true
                    }
                }
            }
        }
    }

    /**
     * Tracks muxer start state across [drainEncoderOutput] calls: the muxer cannot start
     * (and samples cannot be written) until the codec reports its negotiated output format.
     */
    private class MuxerSession(
        private val muxer: MediaMuxer,
    ) {
        var started = false
            private set
        private var trackIndex = -1

        fun onOutputFormatChanged(format: MediaFormat) {
            trackIndex = muxer.addTrack(format)
            muxer.start()
            started = true
        }

        fun writeSample(
            buffer: ByteBuffer,
            info: MediaCodec.BufferInfo,
        ) {
            muxer.writeSampleData(trackIndex, buffer, info)
        }
    }

    private fun readWavSampleRate(file: File): Int {
        FileInputStream(file).use { input ->
            input.skip(24)
            val rateBytes = ByteArray(4)
            input.read(rateBytes)
            return (rateBytes[0].toInt() and 0xFF) or
                ((rateBytes[1].toInt() and 0xFF) shl 8) or
                ((rateBytes[2].toInt() and 0xFF) shl 16) or
                ((rateBytes[3].toInt() and 0xFF) shl 24)
        }
    }

    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val pcm = (sample * 32767).toInt().coerceIn(-32768, 32767).toShort()
            buffer.putShort(pcm)
        }
        return buffer.array()
    }

    private fun floatToShorts(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { index ->
            (samples[index] * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }

    private fun forEachFloatChunk(
        inputPath: String,
        sampleCount: Long,
        onChunk: (FloatArray) -> Unit,
    ) {
        FileInputStream(inputPath).use { input ->
            forEachPcmFloatChunk(
                input = input,
                chunkSamples = STREAM_CHUNK_SAMPLES,
                sampleLimit = sampleCount,
                onChunk = onChunk,
            )
        }
    }

    private inner class PcmFloatFileReader(
        inputPath: String,
        sampleCount: Long,
    ) : AutoCloseable {
        private val input = FileInputStream(inputPath)
        private val reader = PcmFloatStreamReader(input, STREAM_CHUNK_SAMPLES, sampleCount)

        fun readPcm16Chunk(): ByteArray? = reader.readFloatChunk()?.let(::floatToPcm16)

        override fun close() {
            input.close()
        }
    }
}

/** Streams little-endian float PCM without assuming one read ends on a sample boundary. */
internal fun forEachPcmFloatChunk(
    input: InputStream,
    chunkSamples: Int,
    sampleLimit: Long = Long.MAX_VALUE,
    onChunk: (FloatArray) -> Unit,
) {
    val reader = PcmFloatStreamReader(input, chunkSamples, sampleLimit)
    while (true) {
        val chunk = reader.readFloatChunk() ?: return
        onChunk(chunk)
    }
}

private class PcmFloatStreamReader(
    private val input: InputStream,
    chunkSamples: Int,
    sampleLimit: Long,
) {
    private val buffer: ByteArray
    private var remainingSamples = sampleLimit

    init {
        require(chunkSamples > 0) { "chunkSamples must be positive" }
        require(sampleLimit >= 0L) { "sampleLimit must not be negative" }
        buffer = ByteArray(chunkSamples * java.lang.Float.BYTES)
    }

    fun readFloatChunk(): FloatArray? {
        if (remainingSamples == 0L) return null
        var bufferedBytes = 0
        val requestedBytes =
            minOf(
                buffer.size.toLong(),
                remainingSamples.coerceAtMost(Long.MAX_VALUE / java.lang.Float.BYTES) * java.lang.Float.BYTES,
            ).toInt()

        while (bufferedBytes < requestedBytes) {
            val bytesRead = input.read(buffer, bufferedBytes, requestedBytes - bufferedBytes)
            if (bytesRead < 0) {
                if (remainingSamples != Long.MAX_VALUE) {
                    throw IOException("Raw float PCM file contains fewer samples than declared")
                }
                if (bufferedBytes == 0) return null
                if (bufferedBytes % java.lang.Float.BYTES != 0) {
                    throw IOException("Raw float PCM file ends with an incomplete sample")
                }
                break
            }
            if (bytesRead == 0) continue

            bufferedBytes += bytesRead
        }

        val decoded = decodeFloatPcm(buffer, bufferedBytes)
        if (remainingSamples != Long.MAX_VALUE) {
            remainingSamples -= decoded.size
        }
        return decoded
    }
}

private fun decodeFloatPcm(
    buffer: ByteArray,
    byteCount: Int,
): FloatArray {
    val byteBuffer =
        ByteBuffer
            .wrap(buffer, 0, byteCount)
            .order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(byteCount / java.lang.Float.BYTES) { byteBuffer.float }
}
