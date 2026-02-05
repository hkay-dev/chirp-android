package dev.chirpboard.app.feature.transcription.audio

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Processes audio in fixed-size chunks with overlap to prevent word truncation.
 * 
 * Memory budget: ~4MB peak (chunk + overlap + working buffer)
 * 
 * For a 10-minute recording at 16kHz:
 * - Old approach: ~76MB (all samples in memory)
 * - New approach: ~4MB (30s chunk + 2s overlap + working buffer)
 * 
 * @param chunkDurationMs Duration of each chunk in milliseconds (default: 30s)
 * @param overlapDurationMs Overlap between chunks in milliseconds (default: 2s)
 * @param sampleRate Audio sample rate in Hz (default: 16000)
 */
class ChunkedAudioProcessor(
    private val chunkDurationMs: Long = 30_000,
    private val overlapDurationMs: Long = 2_000,
    private val sampleRate: Int = 16000
) {
    private val chunkSize = (chunkDurationMs * sampleRate / 1000).toInt()
    private val overlapSize = (overlapDurationMs * sampleRate / 1000).toInt()
    
    companion object {
        private const val TAG = "ChunkedAudioProcessor"
    }
    
    init {
        require(chunkDurationMs > 0) { "chunkDurationMs must be positive" }
        require(overlapDurationMs >= 0) { "overlapDurationMs must be non-negative" }
        require(overlapDurationMs < chunkDurationMs) { "overlapDurationMs must be less than chunkDurationMs" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        
        Log.d(TAG, "Initialized with chunkSize=$chunkSize samples, overlapSize=$overlapSize samples")
    }
    
    /**
     * Process audio samples in chunks and emit partial transcriptions.
     * 
     * @param audioSource Flow of audio sample arrays from decoder
     * @param transcribe Function to transcribe a chunk of samples
     * @return Flow of partial transcription strings
     */
    fun process(
        audioSource: Flow<FloatArray>,
        transcribe: suspend (FloatArray) -> String
    ): Flow<String> = flow {
        val buffer = ArrayDeque<Float>(chunkSize + overlapSize)
        var chunksProcessed = 0
        
        audioSource.collect { samples ->
            // Add new samples to buffer
            samples.forEach { buffer.addLast(it) }
            
            // Process complete chunks
            while (buffer.size >= chunkSize) {
                val chunk = FloatArray(chunkSize)
                for (i in 0 until chunkSize) {
                    chunk[i] = buffer[i]
                }
                
                chunksProcessed++
                Log.d(TAG, "Processing chunk $chunksProcessed (${chunkSize} samples)")
                
                // Transcribe this chunk
                val partialTranscript = transcribe(chunk)
                if (partialTranscript.isNotBlank()) {
                    emit(partialTranscript)
                }
                
                // Remove processed samples, keeping overlap
                val toRemove = chunkSize - overlapSize
                repeat(toRemove) {
                    buffer.removeFirst()
                }
            }
        }
        
        // Process remaining samples (final partial chunk)
        if (buffer.isNotEmpty()) {
            val remaining = FloatArray(buffer.size)
            buffer.forEachIndexed { index, value ->
                remaining[index] = value
            }
            
            Log.d(TAG, "Processing final chunk (${remaining.size} samples)")
            val finalTranscript = transcribe(remaining)
            if (finalTranscript.isNotBlank()) {
                emit(finalTranscript)
            }
        }
        
        Log.d(TAG, "Completed processing: $chunksProcessed full chunks + 1 final chunk")
    }
    
    /**
     * Process and join all chunks into a single transcript.
     * Handles deduplication of words at chunk boundaries.
     */
    suspend fun processAndJoin(
        audioSource: Flow<FloatArray>,
        transcribe: suspend (FloatArray) -> String
    ): String {
        val parts = mutableListOf<String>()
        
        process(audioSource, transcribe).collect { part ->
            parts.add(part)
        }
        
        return joinTranscripts(parts)
    }
    
    /**
     * Join transcript parts, removing duplicate words at boundaries.
     * 
     * Because we use overlapping chunks, the same words may appear at the end
     * of one chunk and the beginning of the next. This method detects and removes
     * these duplicates by comparing the last few words of each chunk with the
     * first few words of the next.
     */
    internal fun joinTranscripts(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts[0].trim()
        
        val result = StringBuilder(parts[0].trim())
        
        for (i in 1 until parts.size) {
            val prevWords = result.toString().split("\\s+".toRegex()).takeLast(3)
            val currentPart = parts[i].trim()
            val currentWords = currentPart.split("\\s+".toRegex())
            
            // Find overlap - look for repeated words at boundary
            var skipWords = 0
            for (j in minOf(prevWords.size, currentWords.size) downTo 1) {
                val prevEnd = prevWords.takeLast(j)
                val currStart = currentWords.take(j)
                if (prevEnd.map { it.lowercase() } == currStart.map { it.lowercase() }) {
                    skipWords = j
                    break
                }
            }
            
            // Append non-duplicate words
            val toAppend = currentWords.drop(skipWords).joinToString(" ")
            if (toAppend.isNotBlank()) {
                result.append(" ").append(toAppend)
            }
        }
        
        return result.toString()
    }
}
