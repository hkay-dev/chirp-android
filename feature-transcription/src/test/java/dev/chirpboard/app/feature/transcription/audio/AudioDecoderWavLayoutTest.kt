package dev.chirpboard.app.feature.transcription.audio

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioDecoderWavLayoutTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val decoder = AudioDecoder()

    @Test
    fun `canonical 16-bit PCM wav parses with data after 44-byte header`() {
        val file = wavFile(formatTag = 1, bitsPerSample = 16, channels = 1, sampleRate = 16000, dataBytes = 320)

        val layout = decoder.parseWavPcm16Layout(file)

        assertNotNull(layout)
        assertEquals(1, layout!!.channelCount)
        assertEquals(16000, layout.sampleRate)
        assertEquals(44L, layout.dataOffset)
        assertEquals(320L, layout.dataBytes)
    }

    @Test
    fun `32-bit float wav is rejected`() {
        val file = wavFile(formatTag = 3, bitsPerSample = 32, channels = 1, sampleRate = 44100, dataBytes = 320)

        assertNull(decoder.parseWavPcm16Layout(file))
    }

    @Test
    fun `24-bit PCM wav is rejected`() {
        val file = wavFile(formatTag = 1, bitsPerSample = 24, channels = 2, sampleRate = 48000, dataBytes = 480)

        assertNull(decoder.parseWavPcm16Layout(file))
    }

    @Test
    fun `extensible wav with PCM subformat is accepted`() {
        val file =
            wavFile(
                formatTag = 0xFFFE,
                bitsPerSample = 16,
                channels = 2,
                sampleRate = 44100,
                dataBytes = 400,
                extensibleSubFormat = 1,
            )

        val layout = decoder.parseWavPcm16Layout(file)

        assertNotNull(layout)
        assertEquals(2, layout!!.channelCount)
        assertEquals(400L, layout.dataBytes)
    }

    @Test
    fun `extensible wav with float subformat is rejected`() {
        val file =
            wavFile(
                formatTag = 0xFFFE,
                bitsPerSample = 16,
                channels = 2,
                sampleRate = 44100,
                dataBytes = 400,
                extensibleSubFormat = 3,
            )

        assertNull(decoder.parseWavPcm16Layout(file))
    }

    @Test
    fun `LIST chunk before data shifts the data offset`() {
        val listPayload = ByteArray(26)
        val file =
            wavFile(
                formatTag = 1,
                bitsPerSample = 16,
                channels = 1,
                sampleRate = 16000,
                dataBytes = 320,
                preDataChunk = "LIST" to listPayload,
            )

        val layout = decoder.parseWavPcm16Layout(file)

        assertNotNull(layout)
        // 44-byte canonical header + LIST chunk header (8) + payload (26).
        assertEquals(44L + 8L + 26L, layout!!.dataOffset)
        assertEquals(320L, layout.dataBytes)
    }

    @Test
    fun `declared data size wins over trailing bytes`() {
        val file =
            wavFile(formatTag = 1, bitsPerSample = 16, channels = 1, sampleRate = 16000, dataBytes = 320)
        // Simulate a trailing metadata chunk after data.
        file.appendBytes("LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + ByteArray(4))

        val layout = decoder.parseWavPcm16Layout(file)

        assertNotNull(layout)
        assertEquals(320L, layout!!.dataBytes)
    }

    @Test
    fun `zero declared data size falls back to rest of file`() {
        val file =
            wavFile(
                formatTag = 1,
                bitsPerSample = 16,
                channels = 1,
                sampleRate = 16000,
                dataBytes = 320,
                declaredDataSizeOverride = 0,
            )

        val layout = decoder.parseWavPcm16Layout(file)

        assertNotNull(layout)
        assertEquals(320L, layout!!.dataBytes)
    }

    @Test
    fun `non-wav garbage is rejected`() {
        val file = temporaryFolder.newFile("garbage.wav")
        file.writeBytes(ByteArray(256) { 0x42 })

        assertNull(decoder.parseWavPcm16Layout(file))
    }

    @Suppress("LongParameterList")
    private fun wavFile(
        formatTag: Int,
        bitsPerSample: Int,
        channels: Int,
        sampleRate: Int,
        dataBytes: Int,
        extensibleSubFormat: Int? = null,
        preDataChunk: Pair<String, ByteArray>? = null,
        declaredDataSizeOverride: Int? = null,
    ): File {
        val extensible = formatTag == 0xFFFE
        val fmtSize = if (extensible) 40 else 16
        val out = ByteArrayOutputStream()

        fun writeIntLe(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        fun writeShortLe(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        out.write("RIFF".toByteArray())
        writeIntLe(0) // RIFF size: not validated by the parser
        out.write("WAVE".toByteArray())

        out.write("fmt ".toByteArray())
        writeIntLe(fmtSize)
        writeShortLe(formatTag)
        writeShortLe(channels)
        writeIntLe(sampleRate)
        writeIntLe(sampleRate * channels * bitsPerSample / 8)
        writeShortLe(channels * bitsPerSample / 8)
        writeShortLe(bitsPerSample)
        if (extensible) {
            writeShortLe(22) // cbSize
            writeShortLe(bitsPerSample) // valid bits
            writeIntLe(0x3) // channel mask
            writeShortLe(extensibleSubFormat ?: 1)
            out.write(ByteArray(14)) // rest of the subformat GUID
        }

        preDataChunk?.let { (id, payload) ->
            out.write(id.toByteArray())
            writeIntLe(payload.size)
            out.write(payload)
        }

        out.write("data".toByteArray())
        writeIntLe(declaredDataSizeOverride ?: dataBytes)
        out.write(ByteArray(dataBytes))

        val file = temporaryFolder.newFile("test.wav")
        file.writeBytes(out.toByteArray())
        return file
    }
}
