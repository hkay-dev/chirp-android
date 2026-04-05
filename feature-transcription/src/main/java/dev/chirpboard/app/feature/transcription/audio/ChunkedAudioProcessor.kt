package dev.chirpboard.app.feature.transcription.audio

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        var buffer = FloatArray(chunkSize * 2)
        var bufferSize = 0
        var chunksProcessed = 0
        
        audioSource.collect { samples ->
            // Add new samples to buffer
            if (bufferSize + samples.size > buffer.size) {
                val newBuffer = FloatArray(maxOf(buffer.size * 2, bufferSize + samples.size))
                System.arraycopy(buffer, 0, newBuffer, 0, bufferSize)
                buffer = newBuffer
            }
            System.arraycopy(samples, 0, buffer, bufferSize, samples.size)
            bufferSize += samples.size
            
            // Process complete chunks
            while (bufferSize >= chunkSize) {
                val chunk = FloatArray(chunkSize)
                System.arraycopy(buffer, 0, chunk, 0, chunkSize)
                
                chunksProcessed++
                Log.d(TAG, "Processing chunk $chunksProcessed (${chunkSize} samples)")
                
                // Transcribe this chunk
                val partialTranscript = transcribe(chunk)
                if (partialTranscript.isNotBlank()) {
                    emit(partialTranscript)
                }
                
                // Remove processed samples, keeping overlap
                val toRemove = chunkSize - overlapSize
                val remaining = bufferSize - toRemove
                System.arraycopy(buffer, toRemove, buffer, 0, remaining)
                bufferSize = remaining
            }
        }
        
        // Process remaining samples (final partial chunk)
        if (bufferSize > 0) {
            val remaining = FloatArray(bufferSize)
            System.arraycopy(buffer, 0, remaining, 0, bufferSize)
            
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
    internal suspend fun joinTranscripts(parts: List<String>): String = withContext(Dispatchers.Default) {
        if (parts.isEmpty()) return@withContext ""
        if (parts.size == 1) return@withContext parts[0].trim()
        
        val result = StringBuilder(parts[0].trim())
        
        var prevWords = parts[0].trim().split("\\s+".toRegex()).takeLast(3)
        
        for (i in 1 until parts.size) {
            val currentPart = parts[i].trim()
            if (currentPart.isEmpty()) continue
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
            val toAppend = currentWords.drop(skipWords)
            if (toAppend.isNotEmpty()) {
                result.append(" ").append(toAppend.joinToString(" "))
                prevWords = (prevWords + toAppend).takeLast(3)
            }
        }
        
        result.toString()
    }
}
