package dev.chirpboard.app.core.audio.recorder

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioEncoderPcmStreamTest {
    @Test
    fun `float PCM reader carries partial samples across short reads`() {
        val expected = floatArrayOf(-1f, -0.25f, 0f, 0.5f, 1f)
        val bytes =
            ByteBuffer
                .allocate(expected.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { expected.forEach { sample -> putFloat(sample) } }
                .array()
        for (maxReadBytes in 1..3) {
            val decoded = mutableListOf<Float>()

            forEachPcmFloatChunk(
                input = ShortReadInputStream(bytes, maxReadBytes),
                chunkSamples = 2,
            ) { chunk -> decoded += chunk.toList() }

            assertArrayEquals(expected, decoded.toFloatArray(), 0f)
        }
    }

    @Test
    fun `float PCM reader rejects a truncated final sample`() {
        val completeFloat =
            ByteBuffer
                .allocate(Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0.5f)
                .array()
        for (trailingByteCount in 1..3) {
            val truncated = completeFloat + ByteArray(trailingByteCount) { 1 }

            assertThrows(IOException::class.java) {
                forEachPcmFloatChunk(
                    input = ShortReadInputStream(truncated, maxReadBytes = 2),
                    chunkSamples = 2,
                    onChunk = {},
                )
            }
        }
    }

    @Test
    fun `float PCM reader stops at the trusted sample limit`() {
        val bytes = floatBytes(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        val decoded = mutableListOf<Float>()

        forEachPcmFloatChunk(
            input = ShortReadInputStream(bytes, maxReadBytes = 3),
            chunkSamples = 3,
            sampleLimit = 2,
        ) { chunk -> decoded += chunk.toList() }

        assertArrayEquals(floatArrayOf(0.1f, 0.2f), decoded.toFloatArray(), 0f)
    }

    @Test
    fun `float PCM reader rejects a file shorter than its trusted count`() {
        val bytes = floatBytes(floatArrayOf(0.1f, 0.2f))

        assertThrows(IOException::class.java) {
            forEachPcmFloatChunk(
                input = ShortReadInputStream(bytes, maxReadBytes = 3),
                chunkSamples = 3,
                sampleLimit = 3,
                onChunk = {},
            )
        }
    }

    private fun floatBytes(samples: FloatArray): ByteArray =
        ByteBuffer
            .allocate(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach(::putFloat) }
            .array()

    private class ShortReadInputStream(
        private val bytes: ByteArray,
        private val maxReadBytes: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position >= bytes.size) {
                -1
            } else {
                bytes[position++].toInt() and 0xFF
            }

        override fun read(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, maxReadBytes, bytes.size - position)
            bytes.copyInto(
                destination = target,
                destinationOffset = offset,
                startIndex = position,
                endIndex = position + count,
            )
            position += count
            return count
        }
    }
}
