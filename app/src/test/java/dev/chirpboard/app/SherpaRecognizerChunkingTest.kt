package dev.chirpboard.app

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the in-memory chunking decision and the slice streaming that protect long
 * dictations from a single giant sherpa utterance (PIPE-06: native OOM risk).
 */
class SherpaRecognizerChunkingTest {
    private companion object {
        const val SAMPLE_RATE = 16_000
    }

    @Test
    fun `short captures stay on the single-utterance path`() {
        assertFalse(inMemoryDecodeExceedsSingleUtteranceLimit(0, SAMPLE_RATE))
        assertFalse(inMemoryDecodeExceedsSingleUtteranceLimit(SAMPLE_RATE * 30, SAMPLE_RATE))
        assertFalse(
            inMemoryDecodeExceedsSingleUtteranceLimit(
                SAMPLE_RATE * SINGLE_UTTERANCE_MAX_SECONDS,
                SAMPLE_RATE,
            ),
        )
    }

    @Test
    fun `captures beyond the limit are chunked`() {
        assertTrue(
            inMemoryDecodeExceedsSingleUtteranceLimit(
                SAMPLE_RATE * SINGLE_UTTERANCE_MAX_SECONDS + 1,
                SAMPLE_RATE,
            ),
        )
        // The recorder's 10-minute cap is the worst case the dialog/service can produce.
        assertTrue(inMemoryDecodeExceedsSingleUtteranceLimit(SAMPLE_RATE * 60 * 10, SAMPLE_RATE))
    }

    @Test
    fun `slice flow preserves every sample in order without re-buffering the whole capture`() =
        runTest {
            val samples = FloatArray(10) { it.toFloat() }

            val slices = samples.asSliceFlow(sliceSize = 4).toList()

            assertEquals(3, slices.size)
            assertEquals(4, slices[0].size)
            assertEquals(4, slices[1].size)
            assertEquals(2, slices[2].size)
            val rejoined = slices.flatMap { it.toList() }
            assertEquals(samples.toList(), rejoined)
        }

    @Test
    fun `slice flow emits a single slice for short buffers`() =
        runTest {
            val samples = FloatArray(3) { it.toFloat() }

            val slices = samples.asSliceFlow(sliceSize = 16_000).toList()

            assertEquals(1, slices.size)
            assertEquals(samples.toList(), slices.single().toList())
        }

    @Test
    fun `slice flow of an empty buffer emits nothing`() =
        runTest {
            assertTrue(FloatArray(0).asSliceFlow(sliceSize = 4).toList().isEmpty())
        }
}
