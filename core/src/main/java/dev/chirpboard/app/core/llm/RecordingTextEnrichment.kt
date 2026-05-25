package dev.chirpboard.app.core.llm

/**
 * LLM-backed enrichment for saved recordings (title and summary generation).
 */
interface RecordingTextEnrichment {
    suspend fun generateTitle(transcript: String): Result<String>

    suspend fun generateSummary(transcript: String): Result<String>
}
