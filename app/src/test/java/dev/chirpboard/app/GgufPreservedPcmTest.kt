package dev.chirpboard.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufPreservedPcmTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `preserved PCM streams exact little endian samples in bounded slices`() = runTest {
        val file = temporaryFolder.newFile("capture.f32pcm")
        val samples = floatArrayOf(0.25f, -0.5f, 0.75f)
        file.writeBytes(
            ByteBuffer.allocate(samples.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { samples.forEach(::putFloat) }
                .array(),
        )

        val slices = preservedPcmFloatFlow(file.absolutePath, samples.size.toLong(), sliceSamples = 2).toList()

        assertArrayEquals(floatArrayOf(0.25f, -0.5f), slices[0], 0f)
        assertArrayEquals(floatArrayOf(0.75f), slices[1], 0f)
    }

    @Test
    fun `preserved PCM rejects an untrusted file length`() = runTest {
        val file = temporaryFolder.newFile("truncated.f32pcm").apply { writeBytes(ByteArray(4)) }

        val failure =
            runCatching {
                preservedPcmFloatFlow(file.absolutePath, sampleCount = 2, sliceSamples = 2).toList()
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
