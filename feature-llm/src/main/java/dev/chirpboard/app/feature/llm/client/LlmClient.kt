package dev.chirpboard.app.feature.llm.client

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
    suspend fun process(
        text: String,
        systemPrompt: String,
    ): Result<String>

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

    /**
     * Generate a scoped response for a selected transcript passage.
     * @param action The contextual action to run
     * @param passage The selected transcript passage
     * @return Result containing the generated response or error
     */
    suspend fun generateTranscriptPassageResponse(
        action: TranscriptPassageAction,
        passage: String,
    ): Result<String>

    /**
     * Generate grouped structured outcomes for a transcript.
     * @param transcript The transcript to extract from
     * @return Result containing grouped tasks, decisions, and follow-ups or error
     */
    suspend fun generateStructuredOutcomeExtraction(
        transcript: String,
    ): Result<StructuredOutcomeExtraction>

    /**
     * Generate a chat response based on the transcript and previous messages.
     * @param transcript The transcript to reference
     * @param messages The chat history
     * @return Result containing the generated response or error
     */
    suspend fun generateChatResponse(
        transcript: String,
        messages: List<dev.chirpboard.app.feature.llm.model.ChatMessage>,
    ): Result<String>
}
