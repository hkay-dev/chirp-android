package dev.chirpboard.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class WavFileWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `init writes structurally valid header before close so a crash leaves a repairable file`() {
        val file = File(temporaryFolder.root, "crashed.wav")
        val writer = WavFileWriter(file, sampleRate = 16_000)
        writer.appendPcm16(ByteArray(2048) { 1 }, 2048)

        // Simulate a crash: header sizes were never finalized.
        assertTrue(WavFileWriter.hasValidHeader(file))
        assertFalse(WavFileWriter.hasAccurateHeader(file))

        writer.close()
    }

    @Test
    fun `close finalizes header to match actual data size`() {
        val file = File(temporaryFolder.root, "closed.wav")
        WavFileWriter(file, sampleRate = 44_100).use { writer ->
            writer.appendPcm16(ByteArray(4096) { 1 }, 4096)
        }

        assertTrue(WavFileWriter.hasAccurateHeader(file))
        assertEquals(44_100, readSampleRate(file))
        assertEquals(4096, readDataSize(file))
    }

    @Test
    fun `repairHeader fixes stale sizes while preserving sample rate`() {
        val file = File(temporaryFolder.root, "stale.wav")
        WavFileWriter(file, sampleRate = 48_000).use { writer ->
            writer.appendPcm16(ByteArray(2048) { 1 }, 2048)
        }
        // Regress the size fields to the crash-time placeholder values.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.writeIntLittleEndian(36)
            raf.seek(40)
            raf.writeIntLittleEndian(0)
        }
        assertFalse(WavFileWriter.hasAccurateHeader(file))

        assertTrue(WavFileWriter.repairHeaderIfNeeded(file))

        assertTrue(WavFileWriter.hasAccurateHeader(file))
        assertEquals(48_000, readSampleRate(file))
        assertEquals(2048, readDataSize(file))
    }

    @Test
    fun `repairHeader rebuilds fully zeroed header with fallback sample rate`() {
        val file = File(temporaryFolder.root, "zeroed.wav")
        WavFileWriter(file, sampleRate = 16_000).use { writer ->
            writer.appendPcm16(ByteArray(2048) { 1 }, 2048)
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(ByteArray(WavFileWriter.WAV_HEADER_BYTES))
        }
        assertFalse(WavFileWriter.hasValidHeader(file))

        assertTrue(WavFileWriter.repairHeader(file))

        assertTrue(WavFileWriter.hasAccurateHeader(file))
        assertEquals(WavFileWriter.DEFAULT_REPAIR_SAMPLE_RATE, readSampleRate(file))
        assertEquals(2048, readDataSize(file))
    }

    @Test
    fun `repairHeaderIfNeeded keeps already accurate files intact`() {
        val file = File(temporaryFolder.root, "accurate.wav")
        WavFileWriter(file, sampleRate = 22_050).use { writer ->
            writer.appendPcm16(ByteArray(1024) { 1 }, 1024)
        }
        val before = file.readBytes()

        assertTrue(WavFileWriter.repairHeaderIfNeeded(file))

        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `repairHeader refuses files shorter than a header`() {
        val file = File(temporaryFolder.root, "tiny.wav")
        file.writeBytes(ByteArray(10))

        assertFalse(WavFileWriter.repairHeader(file))
    }

    private fun readSampleRate(file: File): Int =
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(24)
            raf.readIntLittleEndian()
        }

    private fun readDataSize(file: File): Int =
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(40)
            raf.readIntLittleEndian()
        }
}

private fun RandomAccessFile.writeIntLittleEndian(value: Int) {
    write(
        byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte(),
        ),
    )
}

private fun RandomAccessFile.readIntLittleEndian(): Int {
    val bytes = ByteArray(4)
    readFully(bytes)
    return (bytes[0].toInt() and 0xFF) or
        ((bytes[1].toInt() and 0xFF) shl 8) or
        ((bytes[2].toInt() and 0xFF) shl 16) or
        ((bytes[3].toInt() and 0xFF) shl 24)
}
