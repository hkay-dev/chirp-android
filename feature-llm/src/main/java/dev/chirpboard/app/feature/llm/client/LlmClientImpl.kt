package dev.chirpboard.app.feature.llm.client

import dev.chirpboard.app.feature.llm.model.ChatMessage
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level LLM client that routes requests through the configured provider backend.
 */
@Singleton
class LlmClientImpl
    @Inject
    constructor(
        private val chatService: LlmChatService,
    ) : LlmClient {
        companion object {
            private const val TITLE_PROMPT = """Generate a brief, descriptive title (5-8 words max) for this voice recording transcript. 
Return ONLY the title text, nothing else. No quotes, no explanation.

Transcript:
"""

            private const val SUMMARY_PROMPT = """Summarize this voice recording transcript in 2-3 sentences.
Focus on the main points and key information.
Return ONLY the summary text, nothing else.

Transcript:
"""

            private const val STRUCTURED_OUTCOME_PROMPT =
                """Extract structured outcomes from this voice recording transcript.
Return valid JSON only, with no markdown fences and no commentary.
Use this exact schema:
{
  \"tasks\": [\"...\"],
  \"decisions\": [\"...\"],
  \"followUps\": [\"...\"]
}
Rules:
- Keep every item grounded in the transcript.
- Use concise action or outcome phrasing.
- Use empty arrays when a group has no items.
- Do not duplicate the same point across groups.

Transcript:
"""
        }

        override suspend fun process(
            text: String,
            systemPrompt: String,
        ): Result<String> {
            val fullPrompt = systemPrompt + text + "\n</transcript>"
            return chatService.completePrompt(fullPrompt)
        }

        override suspend fun generateTitle(transcript: String): Result<String> =
            chatService.completePrompt(TITLE_PROMPT + transcript)

        override suspend fun generateSummary(transcript: String): Result<String> =
            chatService.completePrompt(SUMMARY_PROMPT + transcript)

        override suspend fun generateTranscriptPassageResponse(
            action: TranscriptPassageAction,
            passage: String,
        ): Result<String> = chatService.completePrompt(buildTranscriptPassagePrompt(action = action, passage = passage))

        override suspend fun generateStructuredOutcomeExtraction(
            transcript: String,
        ): Result<StructuredOutcomeExtraction> {
            val response = chatService.completePrompt(STRUCTURED_OUTCOME_PROMPT + transcript)
            if (response.isFailure) {
                return Result.failure(response.exceptionOrNull() ?: Exception("Unknown extraction failure"))
            }
            return parseStructuredOutcomeExtractionResponse(response.getOrThrow())
        }

        override suspend fun generateChatResponse(
            transcript: String,
            messages: List<ChatMessage>,
        ): Result<String> {
            val systemPrompt =
                "You are a helpful assistant analyzing a voice recording transcript. " +
                    "Answer the user's questions about this transcript. Keep answers concise.\n\n" +
                    "Transcript:\n$transcript"
            return chatService.completeChat(systemPrompt, messages)
        }
    }
