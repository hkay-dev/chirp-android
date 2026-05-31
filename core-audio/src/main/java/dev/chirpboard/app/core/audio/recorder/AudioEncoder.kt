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
            RecordingOutputFormat.M4A -> encodePcmFloatFileToM4a(inputPath, sampleRate, outputPath, config)
            RecordingOutputFormat.WAV -> encodePcmFloatFileToWav(inputPath, sampleRate, outputPath)
            RecordingOutputFormat.MP3 -> encodePcmFloatFileToMp3(inputPath, sampleRate, outputPath, config)
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
        var trackIndex = -1
        var muxerStarted = false

        return try {
            File(outputPath).parentFile?.mkdirs()

            val pcmData = floatToPcm16(samples)
            val mediaFormat = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmData.size)
            }

            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

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

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }

                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
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
            runCatching { if (muxerStarted) muxer?.stop() }
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
        sampleRate: Int,
        outputPath: String,
    ): Boolean =
        runCatching {
            File(outputPath).parentFile?.mkdirs()
            WavFileWriter(File(outputPath), sampleRate).use { writer ->
                forEachFloatChunk(inputPath) { chunk ->
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
                forEachFloatChunk(inputPath) { chunk ->
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
        sampleRate: Int,
        outputPath: String,
        config: KeyboardRecordingQualityConfig,
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false
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

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            reader = PcmFloatFileReader(inputPath)

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

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
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
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
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
        onChunk: (FloatArray) -> Unit,
    ) {
        PcmFloatFileReader(inputPath).use { reader ->
            while (true) {
                val chunk = reader.readFloatChunk() ?: break
                onChunk(chunk)
            }
        }
    }

    private inner class PcmFloatFileReader(
        inputPath: String,
    ) : AutoCloseable {
        private val input = FileInputStream(inputPath)
        private val buffer = ByteArray(STREAM_CHUNK_SAMPLES * java.lang.Float.BYTES)

        fun readFloatChunk(): FloatArray? {
            val bytesRead = input.read(buffer)
            if (bytesRead <= 0) {
                return null
            }
            val sampleCount = bytesRead / java.lang.Float.BYTES
            val byteBuffer = ByteBuffer.wrap(buffer, 0, sampleCount * java.lang.Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(sampleCount) { byteBuffer.float }
        }

        fun readPcm16Chunk(): ByteArray? = readFloatChunk()?.let(::floatToPcm16)

        override fun close() {
            input.close()
        }
    }
}
