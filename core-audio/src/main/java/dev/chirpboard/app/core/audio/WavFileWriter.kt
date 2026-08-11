package dev.chirpboard.app.core.audio

import dev.chirpboard.app.core.util.DurableFiles
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

    @Synchronized
    fun appendPcm16(buffer: ByteArray, size: Int) {
        ensureWavSizeWithinLimit(dataBytesWritten, size.toLong())
        randomAccessFile.seek(WAV_HEADER_BYTES + dataBytesWritten)
        randomAccessFile.write(buffer, 0, size)
        dataBytesWritten += size
    }

    @Synchronized
    fun finalizeHeader() {
        writeCanonicalHeader(randomAccessFile, sampleRate, dataBytesWritten)
    }

    /**
     * Pushes live PCM bytes to stable storage without finalizing the still-growing WAV
     * header. Startup recovery repairs its declared sizes from the durable file length.
     */
    @Synchronized
    fun checkpointDurability() {
        randomAccessFile.fd.sync()
    }

    val totalBytes: Long
        get() = WAV_HEADER_BYTES + dataBytesWritten

    @Synchronized
    override fun close() {
        try {
            finalizeHeader()
            randomAccessFile.fd.sync()
        } finally {
            randomAccessFile.close()
            file.parentFile?.let(DurableFiles::syncDirectory)
        }
    }

    companion object {
        const val WAV_HEADER_BYTES = 44
        const val DEFAULT_REPAIR_SAMPLE_RATE = 16_000

        /**
         * Largest data payload whose RIFF/data size fields still fit in the format's
         * unsigned 32-bit fields. Beyond this the header would silently wrap and most
         * players would read a truncated/unreadable file, so writes fail loudly instead.
         */
        const val MAX_WAV_DATA_BYTES: Long = 0xFFFFFFFFL - 44L

        /**
         * Guards against silent 32-bit overflow of the WAV header size fields (~4 GB,
         * about 13.5 hours of 44.1 kHz mono PCM).
         */
        fun ensureWavSizeWithinLimit(
            currentDataBytes: Long,
            additionalBytes: Long,
        ) {
            if (currentDataBytes + additionalBytes > MAX_WAV_DATA_BYTES) {
                throw java.io.IOException(
                    "WAV files larger than 4 GB are not supported — use the M4A output format for recordings this long",
                )
            }
        }
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
        private val PRINTABLE_ASCII_RANGE = 0x20..0x7E

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
            if (hasConsistentTrailingChunks(raf, dataChunk)) {
                // Foreign WAVs may carry chunks (e.g. LIST/INFO) after 'data'; stamping
                // length-derived sizes would over-declare the payload. App-written files
                // always end with the data chunk, so fail closed on such layouts.
                return false
            }
            raf.seek(RIFF_SIZE_OFFSET)
            raf.writeIntLE((raf.length() - RIFF_PREFIX_BYTES).toInt())
            raf.seek(dataChunk.sizeFieldOffset)
            raf.writeIntLE((raf.length() - dataChunk.payloadOffset).toInt())
            return true
        }

        /**
         * True when the declared data size is followed by a chunk sequence that parses
         * cleanly to end-of-file, meaning the header is consistent with a trailing-chunk
         * WAV layout this app never writes (and must not "repair").
         */
        private fun hasConsistentTrailingChunks(
            raf: RandomAccessFile,
            dataChunk: DataChunkLocation,
        ): Boolean {
            var offset = dataChunk.payloadOffset + dataChunk.declaredSize + (dataChunk.declaredSize and 1L)
            if (offset + CHUNK_HEADER_BYTES > raf.length()) return false
            val chunkId = ByteArray(CHUNK_TAG_BYTES.toInt())
            while (offset + CHUNK_HEADER_BYTES <= raf.length()) {
                raf.seek(offset)
                raf.readFully(chunkId)
                if (chunkId.any { it.toInt() !in PRINTABLE_ASCII_RANGE }) return false
                val declaredSize = raf.readIntLE().toLong() and UINT_MASK
                offset += CHUNK_HEADER_BYTES + declaredSize + (declaredSize and 1L)
            }
            return offset == raf.length()
        }

        private fun rebuildZeroedHeader(
            raf: RandomAccessFile,
            fallbackSampleRate: Int,
        ): Boolean {
            if (!hasZeroedHeaderPrefix(raf)) {
                // Not RIFF/WAVE and not a zeroed placeholder header: this is a foreign
                // file that must not be overwritten with a canonical header.
                return false
            }
            val sampleRate = readPlausibleSampleRate(raf) ?: fallbackSampleRate
            writeCanonicalHeader(raf, sampleRate, raf.length() - WAV_HEADER_BYTES)
            return true
        }

        /**
         * True when the RIFF/size/WAVE region is fully zeroed, the signature a crashed
         * legacy recording leaves behind. Every real container format has a non-zero
         * magic in these bytes, so anything else is treated as a foreign file.
         */
        private fun hasZeroedHeaderPrefix(raf: RandomAccessFile): Boolean {
            raf.seek(0)
            val prefix = ByteArray(FIRST_CHUNK_OFFSET.toInt())
            raf.readFully(prefix)
            return prefix.all { it == 0.toByte() }
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
