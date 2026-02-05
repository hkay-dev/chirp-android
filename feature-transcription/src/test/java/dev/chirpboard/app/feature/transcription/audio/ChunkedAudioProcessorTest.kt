package dev.chirpboard.app.feature.transcription.audio

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ChunkedAudioProcessor.
 * 
 * Tests cover:
 * - Processing audio shorter than one chunk
 * - Processing audio exactly one chunk
 * - Processing audio spanning multiple chunks
 * - Verifying overlap is preserved
 * - Verifying word deduplication at boundaries
 */
class ChunkedAudioProcessorTest {

    // Use small values for testing: 100 samples per chunk, 20 sample overlap
    // At "1000 Hz" this represents 100ms chunks with 20ms overlap
    private val testSampleRate = 1000
    private val testChunkDurationMs = 100L
    private val testOverlapDurationMs = 20L
    
    private fun createProcessor() = ChunkedAudioProcessor(
        chunkDurationMs = testChunkDurationMs,
        overlapDurationMs = testOverlapDurationMs,
        sampleRate = testSampleRate
    )

    @Test
    fun `process audio shorter than one chunk`() = runTest {
        val processor = createProcessor()
        val shortAudio = FloatArray(50) { it.toFloat() } // 50 samples < 100 chunk size
        
        val transcriptions = mutableListOf<String>()
        var transcribedSamples: FloatArray? = null
        
        processor.process(flowOf(shortAudio)) { samples ->
            transcribedSamples = samples
            "short audio transcription"
        }.toList().also { transcriptions.addAll(it) }
        
        assertEquals(1, transcriptions.size)
        assertEquals("short audio transcription", transcriptions[0])
        assertEquals(50, transcribedSamples?.size)
    }

    @Test
    fun `process audio exactly one chunk`() = runTest {
        val processor = createProcessor()
        val exactChunk = FloatArray(100) { it.toFloat() } // Exactly 100 samples = 1 chunk
        
        val transcriptions = mutableListOf<String>()
        var callCount = 0
        
        processor.process(flowOf(exactChunk)) { samples ->
            callCount++
            "chunk $callCount"
        }.toList().also { transcriptions.addAll(it) }
        
        // Should process as one full chunk, with remaining overlap as second chunk
        assertEquals(2, callCount)
        assertEquals(2, transcriptions.size)
    }

    @Test
    fun `process audio spanning multiple chunks`() = runTest {
        val processor = createProcessor()
        // 250 samples = 2 full chunks (100 each, minus overlap) + remainder
        // First chunk: 0-99, second starts at 80 (100-20 overlap)
        val multiChunkAudio = FloatArray(250) { it.toFloat() }
        
        val chunkSizes = mutableListOf<Int>()
        
        processor.process(flowOf(multiChunkAudio)) { samples ->
            chunkSizes.add(samples.size)
            "transcription"
        }.toList()
        
        // Should have multiple transcribe calls
        assertTrue("Expected multiple chunks, got ${chunkSizes.size}", chunkSizes.size >= 2)
        
        // First chunks should be full size (100 samples)
        assertEquals(100, chunkSizes[0])
        assertEquals(100, chunkSizes[1])
    }

    @Test
    fun `verify overlap is preserved between chunks`() = runTest {
        val processor = createProcessor()
        // Create audio where we can verify overlap
        val audio = FloatArray(150) { it.toFloat() }
        
        val chunks = mutableListOf<FloatArray>()
        
        processor.process(flowOf(audio)) { samples ->
            chunks.add(samples.copyOf())
            "transcription"
        }.toList()
        
        // With 100-sample chunks and 20-sample overlap:
        // Chunk 1: samples 0-99
        // Chunk 2: samples 80-149 (or less if remaining)
        
        assertTrue("Expected at least 2 chunks", chunks.size >= 2)
        
        // Verify overlap: last 20 samples of chunk 1 should equal first 20 of chunk 2
        if (chunks.size >= 2 && chunks[1].size >= 20) {
            val chunk1End = chunks[0].takeLast(20)
            val chunk2Start = chunks[1].take(20)
            
            for (i in 0 until 20) {
                assertEquals(
                    "Overlap sample $i should match",
                    chunk1End[i],
                    chunk2Start[i],
                    0.001f
                )
            }
        }
    }

    @Test
    fun `joinTranscripts removes duplicate words at boundaries`() {
        val processor = createProcessor()
        
        // Test case: overlapping words at boundary
        val parts = listOf(
            "the quick brown fox",
            "brown fox jumps over",
            "over the lazy dog"
        )
        
        val result = processor.joinTranscripts(parts)
        
        assertEquals("the quick brown fox jumps over the lazy dog", result)
    }

    @Test
    fun `joinTranscripts handles single part`() {
        val processor = createProcessor()
        
        val parts = listOf("hello world")
        val result = processor.joinTranscripts(parts)
        
        assertEquals("hello world", result)
    }

    @Test
    fun `joinTranscripts handles empty list`() {
        val processor = createProcessor()
        
        val parts = emptyList<String>()
        val result = processor.joinTranscripts(parts)
        
        assertEquals("", result)
    }

    @Test
    fun `joinTranscripts handles case-insensitive deduplication`() {
        val processor = createProcessor()
        
        // "The" and "the" should be recognized as duplicates
        val parts = listOf(
            "Hello The",
            "the World"
        )
        
        val result = processor.joinTranscripts(parts)
        
        assertEquals("Hello The World", result)
    }

    @Test
    fun `joinTranscripts handles no overlap`() {
        val processor = createProcessor()
        
        val parts = listOf(
            "first sentence",
            "completely different"
        )
        
        val result = processor.joinTranscripts(parts)
        
        assertEquals("first sentence completely different", result)
    }

    @Test
    fun `processAndJoin combines chunks correctly`() = runTest {
        val processor = createProcessor()
        val audio = FloatArray(250) { it.toFloat() }
        
        var chunkIndex = 0
        val transcriptions = listOf(
            "the quick brown",
            "brown fox jumps",
            "jumps high"
        )
        
        val result = processor.processAndJoin(flowOf(audio)) { _ ->
            val transcript = transcriptions.getOrElse(chunkIndex) { "" }
            chunkIndex++
            transcript
        }
        
        // Should deduplicate overlapping words
        assertEquals("the quick brown fox jumps high", result)
    }

    @Test
    fun `process handles empty audio`() = runTest {
        val processor = createProcessor()
        val emptyAudio = FloatArray(0)
        
        val transcriptions = processor.process(flowOf(emptyAudio)) { _ ->
            "should not be called"
        }.toList()
        
        assertTrue("Empty audio should produce no transcriptions", transcriptions.isEmpty())
    }

    @Test
    fun `process handles blank transcription results`() = runTest {
        val processor = createProcessor()
        val audio = FloatArray(50) { it.toFloat() }
        
        val transcriptions = processor.process(flowOf(audio)) { _ ->
            "   " // Blank transcription
        }.toList()
        
        // Blank results should be filtered out
        assertTrue("Blank transcriptions should be filtered", transcriptions.isEmpty())
    }

    @Test
    fun `process handles multiple flow emissions`() = runTest {
        val processor = createProcessor()
        
        // Simulate decoder emitting multiple small chunks
        val chunk1 = FloatArray(30) { it.toFloat() }
        val chunk2 = FloatArray(30) { (it + 30).toFloat() }
        val chunk3 = FloatArray(30) { (it + 60).toFloat() }
        val chunk4 = FloatArray(30) { (it + 90).toFloat() }
        
        val transcribeCalls = mutableListOf<Int>()
        
        processor.process(flowOf(chunk1, chunk2, chunk3, chunk4)) { samples ->
            transcribeCalls.add(samples.size)
            "transcription"
        }.toList()
        
        // 120 total samples with 100-sample chunks should produce:
        // - 1 full chunk when buffer reaches 100
        // - 1 final chunk with remaining samples
        assertTrue("Expected transcribe to be called", transcribeCalls.isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects negative chunk duration`() {
        ChunkedAudioProcessor(
            chunkDurationMs = -1000,
            overlapDurationMs = 2000,
            sampleRate = 16000
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects overlap greater than chunk`() {
        ChunkedAudioProcessor(
            chunkDurationMs = 1000,
            overlapDurationMs = 2000,
            sampleRate = 16000
        )
    }
}
