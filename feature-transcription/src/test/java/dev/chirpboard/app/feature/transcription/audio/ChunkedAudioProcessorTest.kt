package dev.chirpboard.app.feature.transcription.audio

import dev.chirpboard.app.core.transcription.RecognizedWordTiming
import dev.chirpboard.app.feature.transcription.ChunkTranscription
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChunkedAudioProcessorTest {

    @Test
    fun `process yields expected chunks`() = runTest {
        val processor = ChunkedAudioProcessor(
            chunkDurationMs = 2000,
            overlapDurationMs = 1000,
            sampleRate = 10,
        )
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
            sampleRate = 10,
        )
        val sourceSamples = FloatArray(35) { it.toFloat() }
        val flowSource = flowOf(sourceSamples)

        var callCount = 0
        val joined = processor.processAndJoin(flowSource) {
            callCount++
            when (callCount) {
                1 -> "First part of the"
                2 -> "of the second part"
                else -> "part finally ends"
            }
        }

        assertEquals("First part of the second part finally ends", joined)
    }

    @Test
    fun `processAndJoinDetailed keeps recording relative word timing`() = runTest {
        val processor = ChunkedAudioProcessor(
            chunkDurationMs = 2000,
            overlapDurationMs = 1000,
            sampleRate = 10,
        )
        val sourceSamples = FloatArray(35) { it.toFloat() }
        val flowSource = flowOf(sourceSamples)

        var callCount = 0
        val joined = processor.processAndJoinDetailed(flowSource) {
            callCount++
            when (callCount) {
                1 -> ChunkTranscription(
                    text = "First part of the",
                    wordTimings = listOf(
                        RecognizedWordTiming("First", 0L, 100L),
                        RecognizedWordTiming("part", 100L, 200L),
                        RecognizedWordTiming("of", 200L, 300L),
                        RecognizedWordTiming("the", 300L, 400L),
                    ),
                )
                2 -> ChunkTranscription(
                    text = "of the second part",
                    wordTimings = listOf(
                        RecognizedWordTiming("of", 0L, 100L),
                        RecognizedWordTiming("the", 100L, 200L),
                        RecognizedWordTiming("second", 200L, 300L),
                        RecognizedWordTiming("part", 300L, 400L),
                    ),
                )
                else -> ChunkTranscription(
                    text = "part finally ends",
                    wordTimings = listOf(
                        RecognizedWordTiming("part", 0L, 100L),
                        RecognizedWordTiming("finally", 100L, 200L),
                        RecognizedWordTiming("ends", 200L, 300L),
                    ),
                )
            }
        }

        assertEquals("First part of the second part finally ends", joined.text)
        requireNotNull(joined.wordTimings)
        assertEquals(8, joined.wordTimings.size)
        assertEquals(1200L, joined.wordTimings[4].startTimestampMs)
        assertEquals(2200L, joined.wordTimings[7].startTimestampMs)
    }

    @Test
    fun `processAndJoinDetailed drops timing when a chunk is untimed`() = runTest {
        val processor = ChunkedAudioProcessor(
            chunkDurationMs = 2000,
            overlapDurationMs = 1000,
            sampleRate = 10,
        )
        val sourceSamples = FloatArray(25) { it.toFloat() }
        val flowSource = flowOf(sourceSamples)

        var callCount = 0
        val joined = processor.processAndJoinDetailed(flowSource) {
            callCount++
            when (callCount) {
                1 -> ChunkTranscription(
                    text = "hello there",
                    wordTimings = listOf(
                        RecognizedWordTiming("hello", 0L, 100L),
                        RecognizedWordTiming("there", 100L, 200L),
                    ),
                )
                else -> ChunkTranscription(text = "there world", wordTimings = null)
            }
        }

        assertEquals("hello there world", joined.text)
        assertNull(joined.wordTimings)
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
