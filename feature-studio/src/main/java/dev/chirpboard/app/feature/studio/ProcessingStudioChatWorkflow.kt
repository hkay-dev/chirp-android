package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.model.ChatMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.util.UUID

internal data class StudioChatExchangeResult(
    val messages: ImmutableList<ChatMessage>,
    val isTyping: Boolean,
)

internal fun createStudioChatMessage(
    text: String,
    isFromUser: Boolean,
): ChatMessage =
    ChatMessage(
        id = UUID.randomUUID().toString(),
        text = text,
        isFromUser = isFromUser,
        timestamp = System.currentTimeMillis(),
    )

internal suspend fun completeStudioChatExchange(
    llmClient: LlmClient,
    transcriptText: String,
    messagesWithUser: ImmutableList<ChatMessage>,
): StudioChatExchangeResult {
    val result = llmClient.generateChatResponse(transcriptText, messagesWithUser)
    val aiText = result.getOrNull() ?: "Sorry, I encountered an error."
    val aiMessage = createStudioChatMessage(aiText, isFromUser = false)

    return StudioChatExchangeResult(
        messages = (messagesWithUser + aiMessage).toImmutableList(),
        isTyping = false,
    )
}
