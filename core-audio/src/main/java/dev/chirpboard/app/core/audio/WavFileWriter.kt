package dev.chirpboard.app.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes 16-bit PCM mono WAV files with a header that can be finalized after streaming.
 *
 * A structurally valid header (with placeholder sizes) is written at init so a process
 * crash mid-recording leaves a repairable file instead of 44 zero bytes. [repairHeader]
 * rebuilds zeroed or stale-size headers from the actual bytes on disk.
 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
) : AutoCloseable {
    private val randomAccessFile = RandomAccessFile(file, "rw")
    private var dataBytesWritten = 0L

    init {
        file.parentFile?.mkdirs()
        randomAccessFile.setLength(0)
        writeCanonicalHeader(randomAccessFile, sampleRate, dataBytes = 0L)
    }

    fun appendPcm16(buffer: ByteArray, size: Int) {
        randomAccessFile.seek(WAV_HEADER_BYTES + dataBytesWritten)
        randomAccessFile.write(buffer, 0, size)
        dataBytesWritten += size
    }

    fun finalizeHeader() {
        writeCanonicalHeader(randomAccessFile, sampleRate, dataBytesWritten)
    }

    val totalBytes: Long
        get() = WAV_HEADER_BYTES + dataBytesWritten

    override fun close() {
        finalizeHeader()
        randomAccessFile.close()
    }

    companion object {
        const val WAV_HEADER_BYTES = 44
        const val DEFAULT_REPAIR_SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val BITS_PER_SAMPLE = 16
        private const val RIFF_CHUNK_REMAINDER = 36L
        private const val RIFF_PREFIX_BYTES = 8L
        private const val RIFF_SIZE_OFFSET = 4L
        private const val WAVE_TAG_OFFSET = 8L
        private const val FIRST_CHUNK_OFFSET = 12L
        private const val SAMPLE_RATE_OFFSET = 24L
        private const val FMT_CHUNK_SIZE = 16
        private const val CHUNK_HEADER_BYTES = 8L
        private const val CHUNK_TAG_BYTES = 4L
        private const val PCM_FORMAT: Short = 1
        private const val MONO_CHANNELS: Short = 1
        private const val MIN_PLAUSIBLE_SAMPLE_RATE = 8_000
        private const val MAX_PLAUSIBLE_SAMPLE_RATE = 192_000
        private const val UINT_MASK = 0xFFFFFFFFL

        fun floatToPcm16(samples: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(samples.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val pcm = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                buffer.putShort(pcm)
            }
            return buffer.array()
        }

        fun hasValidHeader(file: File): Boolean =
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    if (raf.length() < WAV_HEADER_BYTES) return false
                    val riff = ByteArray(CHUNK_TAG_BYTES.toInt())
                    raf.readFully(riff)
                    String(riff, Charsets.US_ASCII) == "RIFF" &&
                        raf.readIntLE() > 0 &&
                        run {
                            val wave = ByteArray(CHUNK_TAG_BYTES.toInt())
                            raf.readFully(wave)
                            String(wave, Charsets.US_ASCII) == "WAVE"
                        }
                }
            }.getOrDefault(false)

        /**
         * True when the header structure is valid AND the declared data size matches the
         * PCM bytes actually on disk, so the whole recording is visible to players.
         */
        fun hasAccurateHeader(file: File): Boolean =
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    if (!isRiffWave(raf)) return false
                    val dataChunk = findDataChunk(raf) ?: return false
                    dataChunk.declaredSize > 0 &&
                        dataChunk.declaredSize == raf.length() - dataChunk.payloadOffset
                }
            }.getOrDefault(false)

        /**
         * Rebuilds the header from the actual file length. Handles stale-size headers
         * (sizes never finalized before a crash) and fully zeroed legacy placeholder
         * headers, for which [fallbackSampleRate] is used when no plausible rate survives.
         */
        fun repairHeader(
            file: File,
            fallbackSampleRate: Int = DEFAULT_REPAIR_SAMPLE_RATE,
        ): Boolean =
            runCatching {
                RandomAccessFile(file, "rw").use { raf ->
                    if (raf.length() < WAV_HEADER_BYTES) return false
                    if (isRiffWave(raf)) {
                        repairChunkSizes(raf)
                    } else {
                        rebuildZeroedHeader(raf, fallbackSampleRate)
                    }
                }
            }.getOrDefault(false)

        fun repairHeaderIfNeeded(
            file: File,
            fallbackSampleRate: Int = DEFAULT_REPAIR_SAMPLE_RATE,
        ): Boolean = hasAccurateHeader(file) || repairHeader(file, fallbackSampleRate)

        private fun repairChunkSizes(raf: RandomAccessFile): Boolean {
            val dataChunk = findDataChunk(raf) ?: return false
            raf.seek(RIFF_SIZE_OFFSET)
            raf.writeIntLE((raf.length() - RIFF_PREFIX_BYTES).toInt())
            raf.seek(dataChunk.sizeFieldOffset)
            raf.writeIntLE((raf.length() - dataChunk.payloadOffset).toInt())
            return true
        }

        private fun rebuildZeroedHeader(
            raf: RandomAccessFile,
            fallbackSampleRate: Int,
        ): Boolean {
            val sampleRate = readPlausibleSampleRate(raf) ?: fallbackSampleRate
            writeCanonicalHeader(raf, sampleRate, raf.length() - WAV_HEADER_BYTES)
            return true
        }

        private fun readPlausibleSampleRate(raf: RandomAccessFile): Int? =
            runCatching {
                raf.seek(SAMPLE_RATE_OFFSET)
                raf.readIntLE().takeIf { it in MIN_PLAUSIBLE_SAMPLE_RATE..MAX_PLAUSIBLE_SAMPLE_RATE }
            }.getOrNull()

        private fun isRiffWave(raf: RandomAccessFile): Boolean {
            if (raf.length() < WAV_HEADER_BYTES) return false
            val tag = ByteArray(CHUNK_TAG_BYTES.toInt())
            raf.seek(0)
            raf.readFully(tag)
            if (String(tag, Charsets.US_ASCII) != "RIFF") return false
            raf.seek(WAVE_TAG_OFFSET)
            raf.readFully(tag)
            return String(tag, Charsets.US_ASCII) == "WAVE"
        }

        private fun findDataChunk(raf: RandomAccessFile): DataChunkLocation? {
            var offset = FIRST_CHUNK_OFFSET
            val chunkId = ByteArray(CHUNK_TAG_BYTES.toInt())
            while (offset + CHUNK_HEADER_BYTES <= raf.length()) {
                raf.seek(offset)
                raf.readFully(chunkId)
                val declaredSize = raf.readIntLE().toLong() and UINT_MASK
                if (String(chunkId, Charsets.US_ASCII) == "data") {
                    return DataChunkLocation(
                        sizeFieldOffset = offset + CHUNK_TAG_BYTES,
                        payloadOffset = offset + CHUNK_HEADER_BYTES,
                        declaredSize = declaredSize,
                    )
                }
                offset += CHUNK_HEADER_BYTES + declaredSize + (declaredSize and 1L)
            }
            return null
        }

        private fun writeCanonicalHeader(
            raf: RandomAccessFile,
            sampleRate: Int,
            dataBytes: Long,
        ) {
            raf.seek(0)
            raf.write("RIFF".toByteArray())
            raf.writeIntLE((RIFF_CHUNK_REMAINDER + dataBytes).toInt())
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.writeIntLE(FMT_CHUNK_SIZE)
            raf.writeShortLE(PCM_FORMAT)
            raf.writeShortLE(MONO_CHANNELS)
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(sampleRate * BYTES_PER_SAMPLE)
            raf.writeShortLE(BYTES_PER_SAMPLE.toShort())
            raf.writeShortLE(BITS_PER_SAMPLE.toShort())
            raf.write("data".toByteArray())
            raf.writeIntLE(dataBytes.toInt())
        }

        private data class DataChunkLocation(
            val sizeFieldOffset: Long,
            val payloadOffset: Long,
            val declaredSize: Long,
        )
    }
}

private fun RandomAccessFile.writeIntLE(value: Int) {
    write(
        byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte(),
        ),
    )
}

private fun RandomAccessFile.writeShortLE(value: Short) {
    val intValue = value.toInt()
    write(
        byteArrayOf(
            (intValue and 0xFF).toByte(),
            (intValue shr 8 and 0xFF).toByte(),
        ),
    )
}

private fun RandomAccessFile.readIntLE(): Int {
    val bytes = ByteArray(4)
    readFully(bytes)
    return (bytes[0].toInt() and 0xFF) or
        ((bytes[1].toInt() and 0xFF) shl 8) or
        ((bytes[2].toInt() and 0xFF) shl 16) or
        ((bytes[3].toInt() and 0xFF) shl 24)
}
