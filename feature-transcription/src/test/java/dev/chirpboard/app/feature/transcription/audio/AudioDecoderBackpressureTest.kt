package dev.chirpboard.app.feature.transcription.audio

import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioDecoderBackpressureTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `decodeAsFlow delivers every chunk to a slow collector`() = runTest {
        val file = temporaryFolder.newFile("slow-collector.wav")
        val expectedSampleCount = AudioDecoder.CHUNK_SIZE * 4 + 1_234
        writeWav(file = file, sampleCount = expectedSampleCount)

        val chunkSizes = mutableListOf<Int>()

        AudioDecoder().decodeAsFlow(file.absolutePath).collect { chunk ->
            delay(10)
            chunkSizes += chunk.size
        }

        assertEquals(5, chunkSizes.size)
        assertEquals(expectedSampleCount, chunkSizes.sum())
        assertEquals(AudioDecoder.CHUNK_SIZE, chunkSizes.first())
        assertEquals(1_234, chunkSizes.last())
    }

    private fun writeWav(
        file: java.io.File,
        sampleCount: Int,
    ) {
        WavFileWriter(file, AudioDecoder.TARGET_SAMPLE_RATE).use { writer ->
            var written = 0
            while (written < sampleCount) {
                val chunkSize = minOf(4_096, sampleCount - written)
                val chunk =
                    FloatArray(chunkSize) { index ->
                        val absolute = written + index
                        ((absolute % 200) - 100) / 100f
                    }
                val pcm = WavFileWriter.floatToPcm16(chunk)
                writer.appendPcm16(pcm, pcm.size)
                written += chunkSize
            }
        }
    }
}
