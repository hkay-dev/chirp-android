package dev.chirpboard.app.feature.transcription.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChunkedAudioProcessorTest {

    @Test
    fun `process yields expected chunks`() = runTest {
        // chunk duration 2s, overlap 1s, sample rate 10 -> chunk size 20, overlap 10
        val processor = ChunkedAudioProcessor(
            chunkDurationMs = 2000,
            overlapDurationMs = 1000,
            sampleRate = 10
        )

        // total 35 samples -> should make chunks
        // C1: 0..19
        // C2: 10..29
        // final chunk: 20..34 (15 samples)
        val sourceSamples = FloatArray(35) { it.toFloat() }
        val flowSource = flowOf(sourceSamples)

        val results = processor.process(flowSource) { chunk ->
            "Chunk size: ${chunk.size}, first: ${chunk.first()}, last: ${chunk.last()}"
        }.toList()

        assertEquals(3, results.size)
        assertEquals("Chunk size: 20, first: 0.0, last: 19.0", results[0])
        assertEquals("Chunk size: 20, first: 10.0, last: 29.0", results[1])
        assertEquals("Chunk size: 15, first: 20.0, last: 34.0", results[2])
    }

    @Test
    fun `joinTranscripts merges parts without overlap`() = runTest {
        val processor = ChunkedAudioProcessor()
        val parts = listOf("Hello world", "this is a test")
        val joined = processor.joinTranscripts(parts)
        assertEquals("Hello world this is a test", joined)
    }

    @Test
    fun `joinTranscripts merges parts with single word overlap`() = runTest {
        val processor = ChunkedAudioProcessor()
        val parts = listOf("Hello world there", "there is a test")
        val joined = processor.joinTranscripts(parts)
        assertEquals("Hello world there is a test", joined)
    }

    @Test
    fun `joinTranscripts merges parts with multiple words overlap`() = runTest {
        val processor = ChunkedAudioProcessor()
        val parts = listOf("Hello world how are", "how are you doing")
        val joined = processor.joinTranscripts(parts)
        assertEquals("Hello world how are you doing", joined)
    }

    @Test
    fun `joinTranscripts handles capitalization differences`() = runTest {
        val processor = ChunkedAudioProcessor()
        val parts = listOf("Hello world HOW ARE", "how are you doing")
        val joined = processor.joinTranscripts(parts)
        assertEquals("Hello world HOW ARE you doing", joined)
    }
    
    @Test
    fun `processAndJoin processes and joins all chunks`() = runTest {
        val processor = ChunkedAudioProcessor(
            chunkDurationMs = 2000,
            overlapDurationMs = 1000,
            sampleRate = 10
        )
        val sourceSamples = FloatArray(35) { it.toFloat() }
        val flowSource = flowOf(sourceSamples)
        
        var callCount = 0
        val joined = processor.processAndJoin(flowSource) { chunk ->
            callCount++
            when (callCount) {
                1 -> "First part of the"
                2 -> "of the second part"
                else -> "part finally ends"
            }
        }
        
        // "First part of the" + "of the second part" -> "First part of the second part"
        // "First part of the second part" + "part finally ends" -> "First part of the second part finally ends"
        assertEquals("First part of the second part finally ends", joined)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `init fails with invalid chunk duration`() {
        ChunkedAudioProcessor(chunkDurationMs = -100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `init fails with invalid overlap duration`() {
        ChunkedAudioProcessor(chunkDurationMs = 1000, overlapDurationMs = 2000)
    }
}
