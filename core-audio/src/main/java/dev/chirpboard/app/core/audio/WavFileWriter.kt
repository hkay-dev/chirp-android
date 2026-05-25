package dev.chirpboard.app.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes 16-bit PCM mono WAV files with a header that can be finalized after streaming.
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
        randomAccessFile.write(ByteArray(WAV_HEADER_BYTES))
    }

    fun appendPcm16(buffer: ByteArray, size: Int) {
        randomAccessFile.seek(WAV_HEADER_BYTES + dataBytesWritten)
        randomAccessFile.write(buffer, 0, size)
        dataBytesWritten += size
    }

    fun finalizeHeader() {
        val totalDataSize = dataBytesWritten
        val riffChunkSize = 36 + totalDataSize
        randomAccessFile.seek(0)
        randomAccessFile.write("RIFF".toByteArray())
        randomAccessFile.writeIntLE(riffChunkSize.toInt())
        randomAccessFile.write("WAVE".toByteArray())
        randomAccessFile.write("fmt ".toByteArray())
        randomAccessFile.writeIntLE(16)
        randomAccessFile.writeShortLE(1)
        randomAccessFile.writeShortLE(1)
        randomAccessFile.writeIntLE(sampleRate)
        randomAccessFile.writeIntLE(sampleRate * BYTES_PER_SAMPLE)
        randomAccessFile.writeShortLE(BYTES_PER_SAMPLE.toShort())
        randomAccessFile.writeShortLE(BITS_PER_SAMPLE.toShort())
        randomAccessFile.write("data".toByteArray())
        randomAccessFile.writeIntLE(totalDataSize.toInt())
    }

    val totalBytes: Long
        get() = WAV_HEADER_BYTES + dataBytesWritten

    override fun close() {
        finalizeHeader()
        randomAccessFile.close()
    }

    companion object {
        const val WAV_HEADER_BYTES = 44
        private const val BYTES_PER_SAMPLE = 2
        private const val BITS_PER_SAMPLE = 16

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
                    val riff = ByteArray(4)
                    raf.readFully(riff)
                    String(riff, Charsets.US_ASCII) == "RIFF" &&
                        raf.readIntLE() > 0 &&
                        run {
                            val wave = ByteArray(4)
                            raf.readFully(wave)
                            String(wave, Charsets.US_ASCII) == "WAVE"
                        }
                }
            }.getOrDefault(false)
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
