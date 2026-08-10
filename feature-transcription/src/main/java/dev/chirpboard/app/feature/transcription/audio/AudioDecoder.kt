package dev.chirpboard.app.feature.transcription.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

// =============================================================================
// Safe MediaFormat Extension Functions
// =============================================================================

private const val MEDIA_FORMAT_TAG = "MediaFormat"

/**
 * Safe accessor for MediaFormat integer values.
 * Returns default if key is missing or value is invalid.
 */
private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
    return try {
        if (containsKey(key)) getInteger(key) else default
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(MEDIA_FORMAT_TAG, "Failed to get MediaFormat key $key, using default $default", e)
        default
    }
}

/**
 * Safe accessor for MediaFormat string values.
 */
private fun MediaFormat.getStringOrNull(key: String): String? {
    return try {
        if (containsKey(key)) getString(key) else null
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(MEDIA_FORMAT_TAG, "Failed to get MediaFormat key $key", e)
        null
    }
}

// =============================================================================
// Format Validation
// =============================================================================

/**
 * Audio format validation result.
 */
sealed class FormatValidationResult {
    data object Valid : FormatValidationResult()
    data class Invalid(val reason: String) : FormatValidationResult()
    data class Unsupported(val format: String) : FormatValidationResult()
}

/**
 * Validate audio format before decoding.
 */
private fun validateFormat(format: MediaFormat): FormatValidationResult {
    val mime = format.getStringOrNull(MediaFormat.KEY_MIME)

    // Check MIME type
    if (mime == null) {
        return FormatValidationResult.Invalid("Unknown audio format")
    }

    if (!mime.startsWith("audio/")) {
        return FormatValidationResult.Unsupported(mime)
    }

    // Validate essential parameters
    val sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, -1)
    if (sampleRate <= 0) {
        return FormatValidationResult.Invalid("Invalid sample rate: $sampleRate")
    }

    val channelCount = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, -1)
    if (channelCount <= 0) {
        return FormatValidationResult.Invalid("Invalid channel count: $channelCount")
    }

    return FormatValidationResult.Valid
}

/**
 * Decodes audio files (M4A, etc.) to PCM float samples for Sherpa-ONNX transcription.
 *
 * Features:
 * - Uses MediaExtractor and MediaCodec for hardware-accelerated decoding
 * - Resamples to 16kHz (Sherpa-ONNX requirement)
 * - Converts stereo to mono
 * - Processes in chunks for memory efficiency
 * - Normalizes samples to [-1.0, 1.0] range
 *
 * WAV inputs prefer direct PCM extraction (see [decodeWavPcmDirect]) because some OEM
 * MediaCodec decoders fail on PCM-in-WAV containers. MediaCodec remains the fallback.
 */
@Singleton
class AudioDecoder @Inject constructor() {

    companion object {
        private const val TAG = "AudioDecoder"

        /** Target sample rate for Sherpa-ONNX */
        const val TARGET_SAMPLE_RATE = 16000

        /** Chunk size in samples (1 second at 16kHz) */
        const val CHUNK_SIZE = 16000

        /** Timeout for MediaCodec operations in microseconds */
        private const val CODEC_TIMEOUT_US = 10_000L

        /** Maximum value for 16-bit PCM normalization */
        private const val MAX_16BIT = 32768f

        private const val WAVE_FORMAT_PCM = 0x0001
        private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
        private const val FMT_CHUNK_MIN_BYTES = 16L
        private const val FMT_EXTENSIBLE_MIN_BYTES = 40L
        private const val UINT32_MASK = 0xFFFFFFFFL
        private const val USHORT_MASK = 0xFFFF
        private val PRINTABLE_ASCII_RANGE = 0x20..0x7E
    }

    /**
     * Decode audio file to PCM float samples.
     *
     * @param filePath Path to M4A audio file
     * @param onChunk Callback for each chunk of samples (suspend for backpressure).
     *                Samples are normalized to [-1.0, 1.0] at 16kHz mono.
     * @return Total number of samples decoded
     * @throws AudioDecoderException if decoding fails
     */
    suspend fun decode(
        filePath: String,
        onChunk: suspend (samples: FloatArray) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) {
            throw AudioDecoderException("Audio file not found: $filePath")
        }

        val wavLayout =
            if (file.extension.equals("wav", ignoreCase = true)) parseWavPcm16Layout(file) else null
        if (wavLayout != null) {
            return@withContext decodeWavPcmDirect(file, wavLayout, onChunk)
        }

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var totalSamples = 0L

        try {
            // Set up MediaExtractor
            extractor = MediaExtractor().apply {
                setDataSource(filePath)
            }

            // Find audio track
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                throw AudioDecoderException("No audio track found in file: $filePath")
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            // Validate format before processing
            when (val validation = validateFormat(format)) {
                is FormatValidationResult.Invalid -> {
                    throw AudioDecoderException("Invalid audio file: ${validation.reason}")
                }
                is FormatValidationResult.Unsupported -> {
                    throw AudioDecoderException("Unsupported format: ${validation.format}")
                }
                FormatValidationResult.Valid -> { /* continue */ }
            }

            // Use safe accessors for all MediaFormat values
            val mimeType = format.getStringOrNull(MediaFormat.KEY_MIME)
                ?: throw AudioDecoderException("Unknown MIME type")
            val sourceSampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            val channelCount = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)

            Log.d(TAG, "Audio format: $mimeType, ${sourceSampleRate}Hz, $channelCount channels")

            // Create and configure decoder
            codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(format, null, null, 0)
            codec.start()

            // Decode audio
            val resampler = Resampler(sourceSampleRate, TARGET_SAMPLE_RATE, channelCount)
            val chunkBuffer = ChunkBuffer(CHUNK_SIZE)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && coroutineContext.isActive) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                // End of stream
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain output
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        Log.d(TAG, "Output format changed: $newFormat")
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // Process decoded PCM data
                            val pcmData = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmData)
                            outputBuffer.clear()

                            // Convert to float samples and resample
                            val samples = resampler.process(pcmData)

                            // Add to chunk buffer and emit full chunks
                            chunkBuffer.add(samples) { chunk ->
                                onChunk(chunk)
                                totalSamples += chunk.size
                            }
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            // Emit any remaining samples
            chunkBuffer.flush { chunk ->
                onChunk(chunk)
                totalSamples += chunk.size
            }

            Log.d(TAG, "Decoded $totalSamples samples from $filePath")
            totalSamples

        } catch (e: AudioDecoderException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val retryLayout =
                if (file.extension.equals("wav", ignoreCase = true)) parseWavPcm16Layout(file) else null
            if (retryLayout != null) {
                Log.w(TAG, "MediaCodec WAV decode failed; retrying with direct PCM reader", e)
                return@withContext decodeWavPcmDirect(file, retryLayout, onChunk)
            }
            if (file.extension.equals("wav", ignoreCase = true) && WavFileWriter.hasValidHeader(file)) {
                // RIFF/WAVE but not canonical 16-bit PCM (24/32-bit, float, ADPCM…) and
                // MediaCodec could not decode it either: reject clearly instead of ever
                // reading the payload with wrong assumptions.
                Log.e(TAG, "Unsupported WAV variant: ${e.message}", e)
                throw AudioDecoderException(
                    "This WAV file uses an unsupported encoding and can't be transcribed",
                    e,
                )
            }
            Log.e(TAG, "Failed to decode audio: ${e.message}", e)
            throw AudioDecoderException("Failed to decode audio: ${e.message}", e)
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Error releasing codec", e)
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Error releasing extractor", e)
            }
        }
    }

    /**
     * Decode audio file and emit samples as a Flow.
     * This allows for memory-efficient streaming processing with chunked transcription.
     * 
     * @param filePath Path to M4A audio file
     * @return Flow of audio sample arrays (normalized to [-1.0, 1.0] at 16kHz mono)
     * @throws AudioDecoderException if decoding fails
     */
    fun decodeAsFlow(filePath: String): Flow<FloatArray> = channelFlow {
        var totalSamples = 0L
        var emittedChunks = 0L
        try {
            totalSamples = decode(filePath) { samples ->
                try {
                    send(samples)
                    emittedChunks++
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    recordDecodeDeliveryFailure(e)
                    throw AudioDecoderException("Failed to deliver decoded audio chunk: ${e.message}", e)
                }
            }
            Log.d(TAG, "Flow completed: emitted $emittedChunks chunks and $totalSamples samples")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Flow error during decode", e)
            throw e
        }
    }.buffer(Channel.RENDEZVOUS)

    private fun recordDecodeDeliveryFailure(error: Throwable) {
        ReliabilityEventLogger
            .scoped(
                stage = ReliabilityStage.TRANSCRIPTION,
                correlationId = ReliabilityEventLogger.newCorrelationId("decode"),
            ).failure("decode_chunk_delivery_failed", error)
    }

    /**
     * Where the verified 16-bit PCM payload of a WAV file lives.
     */
    internal data class WavPcm16Layout(
        val channelCount: Int,
        val sampleRate: Int,
        val dataOffset: Long,
        val dataBytes: Long,
    )

    /**
     * Walks the RIFF chunks and returns the PCM layout only when the file is canonical
     * 16-bit integer PCM (format tag 1, or EXTENSIBLE with a PCM subformat). Returns null
     * for everything else — 24/32-bit, float, ADPCM, compressed payloads — which must go
     * through MediaCodec instead of being misread as 16-bit samples at a fixed offset
     * (previously the reader hardcoded the canonical 44-byte header and decoded garbage
     * for foreign WAVs with extended fmt chunks or LIST metadata before 'data').
     */
    internal fun parseWavPcm16Layout(file: File): WavPcm16Layout? =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() <= WavFileWriter.WAV_HEADER_BYTES) return@use null
                val tag = ByteArray(4)
                raf.seek(0)
                raf.readFully(tag)
                if (String(tag, Charsets.US_ASCII) != "RIFF") return@use null
                raf.seek(8)
                raf.readFully(tag)
                if (String(tag, Charsets.US_ASCII) != "WAVE") return@use null

                var formatTag = -1
                var channelCount = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var pcmSubFormat = false
                var dataOffset = -1L
                var dataBytes = -1L

                var offset = 12L
                while (offset + 8 <= raf.length()) {
                    raf.seek(offset)
                    raf.readFully(tag)
                    if (tag.any { it.toInt() !in PRINTABLE_ASCII_RANGE }) {
                        // Garbage where a chunk id should be: stop scanning rather than
                        // walking megabytes of non-chunk data 8 bytes at a time.
                        break
                    }
                    val chunkId = String(tag, Charsets.US_ASCII)
                    val declaredSize = raf.readIntLe().toLong() and UINT32_MASK
                    val payloadOffset = offset + 8
                    when (chunkId) {
                        "fmt " -> {
                            if (declaredSize < FMT_CHUNK_MIN_BYTES) return@use null
                            formatTag = raf.readShortLe().toInt() and USHORT_MASK
                            channelCount = raf.readShortLe().toInt() and USHORT_MASK
                            sampleRate = raf.readIntLe()
                            raf.skipBytes(6) // byte rate (4) + block align (2)
                            bitsPerSample = raf.readShortLe().toInt() and USHORT_MASK
                            if (formatTag == WAVE_FORMAT_EXTENSIBLE && declaredSize >= FMT_EXTENSIBLE_MIN_BYTES) {
                                raf.skipBytes(2 + 2 + 4) // cbSize + valid bits + channel mask
                                pcmSubFormat = (raf.readShortLe().toInt() and USHORT_MASK) == WAVE_FORMAT_PCM
                            }
                        }
                        "data" -> {
                            dataOffset = payloadOffset
                            dataBytes =
                                if (declaredSize == 0L) {
                                    // Crash-stale placeholder size from the app's own
                                    // writer: data is always the final chunk, so the rest
                                    // of the file is the payload (mirrors repairHeader).
                                    raf.length() - payloadOffset
                                } else {
                                    minOf(declaredSize, raf.length() - payloadOffset)
                                }
                            if (declaredSize == 0L) {
                                break
                            }
                        }
                    }
                    offset = payloadOffset + declaredSize + (declaredSize and 1L)
                }

                val isPcm16 =
                    bitsPerSample == 16 &&
                        (formatTag == WAVE_FORMAT_PCM || (formatTag == WAVE_FORMAT_EXTENSIBLE && pcmSubFormat))
                if (!isPcm16 || channelCount < 1 || sampleRate < 1 || dataOffset < 0 || dataBytes <= 0) {
                    null
                } else {
                    WavPcm16Layout(
                        channelCount = channelCount,
                        sampleRate = sampleRate,
                        dataOffset = dataOffset,
                        dataBytes = dataBytes,
                    )
                }
            }
        }.getOrNull()

    /**
     * Decode 16-bit PCM WAV without MediaCodec. Used for app-produced WAV and as an OEM
     * fallback. [layout] is the verified fmt/data location, so trailing metadata chunks
     * are never misread as audio.
     */
    private suspend fun decodeWavPcmDirect(
        file: File,
        layout: WavPcm16Layout,
        onChunk: suspend (FloatArray) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        RandomAccessFile(file, "r").use { raf ->
            val resampler = Resampler(layout.sampleRate, TARGET_SAMPLE_RATE, layout.channelCount)
            val chunkBuffer = ChunkBuffer(CHUNK_SIZE)
            var totalSamples = 0L
            var remaining = layout.dataBytes

            raf.seek(layout.dataOffset)
            val frameBytes = layout.channelCount * 2
            val readBuffer = ByteArray(CHUNK_SIZE * frameBytes)
            while (remaining > 0) {
                val toRead = minOf(readBuffer.size.toLong(), remaining).toInt()
                // read() may return fewer bytes than requested. Handing a partial buffer
                // to the resampler would shift every later 16-bit sample by the shortfall,
                // so fill the request completely and only ever pass whole frames on.
                var filled = 0
                while (filled < toRead) {
                    val bytesRead = raf.read(readBuffer, filled, toRead - filled)
                    if (bytesRead <= 0) break
                    filled += bytesRead
                }
                if (filled == 0) break
                remaining -= filled
                val usableBytes = filled - (filled % frameBytes)
                if (usableBytes == 0) break

                val pcmData = readBuffer.copyOf(usableBytes)
                val samples = resampler.process(pcmData)
                chunkBuffer.add(samples) { chunk ->
                    onChunk(chunk)
                    totalSamples += chunk.size
                }
            }

            chunkBuffer.flush { chunk ->
                onChunk(chunk)
                totalSamples += chunk.size
            }

            Log.d(TAG, "Direct WAV decode completed: $totalSamples samples from ${file.path}")
            totalSamples
        }
    }

    /**
     * Find the first audio track in the extractor.
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getStringOrNull(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }

    /**
     * Handles resampling and stereo-to-mono conversion.
     */
    private class Resampler(
        private val sourceSampleRate: Int,
        private val targetSampleRate: Int,
        private val channelCount: Int
    ) {
        private val resampleRatio = targetSampleRate.toDouble() / sourceSampleRate

        /**
         * Process raw PCM bytes to resampled mono float samples.
         */
        fun process(pcmData: ByteArray): FloatArray {
            // Convert bytes to 16-bit samples
            val shortSamples = bytesToShorts(pcmData)

            // Convert to mono if stereo
            val monoSamples = if (channelCount > 1) {
                stereoToMono(shortSamples, channelCount)
            } else {
                shortSamples
            }

            // Resample if needed
            val resampled = if (sourceSampleRate != targetSampleRate) {
                resample(monoSamples)
            } else {
                val out = FloatArray(monoSamples.size)
                for (i in monoSamples.indices) {
                    out[i] = monoSamples[i] / MAX_16BIT
                }
                out
            }

            return resampled
        }

        /**
         * Convert byte array to short array (16-bit PCM, little-endian).
         */
        private fun bytesToShorts(bytes: ByteArray): ShortArray {
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shorts)
            return shorts
        }

        /**
         * Convert multi-channel to mono by averaging channels.
         */
        private fun stereoToMono(samples: ShortArray, channels: Int): ShortArray {
            val monoLength = samples.size / channels
            val mono = ShortArray(monoLength)

            for (i in 0 until monoLength) {
                var sum = 0
                for (ch in 0 until channels) {
                    sum += samples[i * channels + ch]
                }
                mono[i] = (sum / channels).toShort()
            }

            return mono
        }

        /**
         * Resample using linear interpolation.
         * Converts to float and normalizes to [-1.0, 1.0] in the same pass.
         */
        private fun resample(samples: ShortArray): FloatArray {
            if (samples.isEmpty()) return FloatArray(0)

            val outputLength = (samples.size * resampleRatio).roundToInt()
            if (outputLength == 0) return FloatArray(0)

            val output = FloatArray(outputLength)

            for (i in 0 until outputLength) {
                val srcIndex = i / resampleRatio
                val srcIndexInt = srcIndex.toInt()
                val fraction = srcIndex - srcIndexInt

                val sample1 = samples.getOrElse(srcIndexInt) { samples.last() }
                val sample2 = samples.getOrElse(srcIndexInt + 1) { samples.last() }

                // Linear interpolation and normalization in one step
                val interpolated = sample1 + (sample2 - sample1) * fraction
                output[i] = (interpolated / MAX_16BIT).toFloat()
            }

            return output
        }
    }

    /**
     * Accumulates samples and emits fixed-size chunks.
     * Uses a primitive FloatArray to avoid autoboxing overhead.
     */
    private class ChunkBuffer(private val chunkSize: Int) {
        private var buffer = FloatArray(chunkSize * 2)
        private var bufferSize = 0

        /**
         * Add samples to buffer and emit full chunks via callback.
         */
        suspend inline fun add(
            samples: FloatArray,
            emit: suspend (FloatArray) -> Unit
        ) {
            if (bufferSize + samples.size > buffer.size) {
                val newBuffer = FloatArray(maxOf(buffer.size * 2, bufferSize + samples.size))
                System.arraycopy(buffer, 0, newBuffer, 0, bufferSize)
                buffer = newBuffer
            }
            System.arraycopy(samples, 0, buffer, bufferSize, samples.size)
            bufferSize += samples.size

            while (bufferSize >= chunkSize) {
                val chunk = FloatArray(chunkSize)
                System.arraycopy(buffer, 0, chunk, 0, chunkSize)
                emit(chunk)
                
                val remaining = bufferSize - chunkSize
                if (remaining > 0) {
                    System.arraycopy(buffer, chunkSize, buffer, 0, remaining)
                }
                bufferSize = remaining
            }
        }

        /**
         * Emit any remaining samples in the buffer.
         */
        suspend inline fun flush(emit: suspend (FloatArray) -> Unit) {
            if (bufferSize > 0) {
                val chunk = FloatArray(bufferSize)
                System.arraycopy(buffer, 0, chunk, 0, bufferSize)
                bufferSize = 0
                emit(chunk)
            }
        }
    }
}

/**
 * Exception thrown when audio decoding fails.
 */
class AudioDecoderException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

private fun RandomAccessFile.readIntLe(): Int {
    val bytes = ByteArray(4)
    readFully(bytes)
    return (bytes[0].toInt() and 0xFF) or
        ((bytes[1].toInt() and 0xFF) shl 8) or
        ((bytes[2].toInt() and 0xFF) shl 16) or
        ((bytes[3].toInt() and 0xFF) shl 24)
}

private fun RandomAccessFile.readShortLe(): Short {
    val bytes = ByteArray(2)
    readFully(bytes)
    return ((bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)).toShort()
}
