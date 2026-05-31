package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingSegmentConcatenatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun concatToExport_encodesDurableWavSegmentToSelectedM4aExport() {
        val audioEncoder = mockk<AudioEncoder>()
        val classUnderTest = RecordingSegmentConcatenator(audioEncoder)
        val segment = File(temporaryFolder.root, "seg-000.wav")
        val export = File(temporaryFolder.root, "recording.m4a")
        writeWav(segment)
        every {
            audioEncoder.encodePcm16WavFile(
                inputPath = segment.absolutePath,
                outputPath = export.absolutePath,
                format = RecordingOutputFormat.M4A,
            )
        } answers {
            export.writeBytes(ByteArray(1024) { 1 })
            true
        }

        val result = classUnderTest.concatToExport(listOf(segment), export)

        assertTrue(result is SegmentConcatResult.Success)
        assertTrue(export.exists())
    }

    private fun writeWav(file: File) {
        WavFileWriter(file, sampleRate = 16_000).use { writer ->
            writer.appendPcm16(ByteArray(2048) { 1 }, 2048)
        }
    }
}
