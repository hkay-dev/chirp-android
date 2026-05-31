package dev.chirpboard.app.feature.transcription.audio

import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.core.transcription.InlineAudioSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InlineAudioSourceStreamsTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `file backed inline audio streams all samples under slow collection`() = runTest {
        val sampleCount = INLINE_AUDIO_SOURCE_CHUNK_SAMPLES * 3 + 77
        val file = temporaryFolder.newFile("dictation.f32pcm")
        writeFloatPcm(file, sampleCount)

        val chunks =
            InlineAudioSource.PcmFloatFile(
                path = file.absolutePath,
                sampleCount = sampleCount.toLong(),
            ).asSampleFlow()
                .onEach { delay(5) }
                .toList()

        assertEquals(4, chunks.size)
        assertEquals(sampleCount, chunks.sumOf { it.size })
        assertEquals(INLINE_AUDIO_SOURCE_CHUNK_SAMPLES, chunks.first().size)
        assertEquals(77, chunks.last().size)
    }

    private fun writeFloatPcm(
        file: java.io.File,
        sampleCount: Int,
    ) {
        file.outputStream().use { output ->
            val buffer = ByteBuffer.allocate(4_096 * java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
            var written = 0
            while (written < sampleCount) {
                buffer.clear()
                val count = minOf(4_096, sampleCount - written)
                repeat(count) { index ->
                    buffer.putFloat(((written + index) % 100) / 100f)
                }
                output.write(buffer.array(), 0, count * java.lang.Float.BYTES)
                written += count
            }
        }
    }
}
