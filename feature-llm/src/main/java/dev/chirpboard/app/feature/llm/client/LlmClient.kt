package dev.chirpboard.app.feature.llm.client

/**
 * Interface for LLM client operations.
 * Abstracts the underlying LLM provider (Gemini, OpenAI, etc.)
 *
 * Each transcript operation takes a [TranscriptLlmContext], which carries the
 * transcript text plus the optional provider/model to route the request to.
 * A null provider/model routes through the active provider.
 */
interface LlmClient {
    /**
     * Process a transcript with a system prompt.
     * @param context The transcript and optional provider/model routing
     * @param systemPrompt The system/instruction prompt
     * @return Result containing processed text or error
     */
    suspend fun process(
        context: TranscriptLlmContext,
        systemPrompt: String,
    ): Result<String>

    /**
     * Generate a title for a transcript.
     * @param context The transcript and optional provider/model routing
     * @return Result containing the generated title or error
     */
    suspend fun generateTitle(context: TranscriptLlmContext): Result<String>

    /**
     * Generate a summary for a transcript.
     * @param context The transcript and optional provider/model routing
     * @return Result containing the generated summary or error
     */
    suspend fun generateSummary(context: TranscriptLlmContext): Result<String>

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
