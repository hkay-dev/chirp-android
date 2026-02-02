package dev.parakeeboard.app.feature.llm.client

/**
 * Interface for LLM client operations.
 * Abstracts the underlying LLM provider (Gemini, OpenAI, etc.)
 */
interface LlmClient {
    /**
     * Process text with a system prompt.
     * @param text The input text to process
     * @param systemPrompt The system/instruction prompt
     * @return Result containing processed text or error
     */
    suspend fun process(text: String, systemPrompt: String): Result<String>

    /**
     * Generate a title for a transcript.
     * @param transcript The transcript to generate a title for
     * @return Result containing the generated title or error
     */
    suspend fun generateTitle(transcript: String): Result<String>

    /**
     * Generate a summary for a transcript.
     * @param transcript The transcript to summarize
     * @return Result containing the generated summary or error
     */
    suspend fun generateSummary(transcript: String): Result<String>
}
