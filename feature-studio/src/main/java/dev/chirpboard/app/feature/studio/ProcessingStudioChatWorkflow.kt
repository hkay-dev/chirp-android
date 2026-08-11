package dev.chirpboard.app.feature.studio

import android.content.Context
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.model.ChatMessage
import kotlinx.collections.immutable.ImmutableList
import java.util.UUID

/** Only the most recent turns travel to the model; unbounded history grows every request. */
internal const val MAX_STUDIO_CHAT_HISTORY_MESSAGES = 20

internal sealed interface StudioChatExchangeOutcome {
    data class Reply(
        val message: ChatMessage,
    ) : StudioChatExchangeOutcome

    data class Failure(
        val displayMessage: String,
    ) : StudioChatExchangeOutcome
}

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
    context: Context,
    llmClient: LlmClient,
    transcriptText: String,
    history: ImmutableList<ChatMessage>,
): StudioChatExchangeOutcome {
    val result =
        llmClient.generateChatResponse(
            transcriptText,
            history.takeLast(MAX_STUDIO_CHAT_HISTORY_MESSAGES),
        )
    val reply = result.getOrNull()
    return if (reply != null) {
        StudioChatExchangeOutcome.Reply(createStudioChatMessage(reply, isFromUser = false))
    } else {
        // ERR-11/I18N-05: classify the failure into actionable copy; it surfaces as a snackbar,
        // never as a fake assistant bubble that would replay into later requests as history.
        StudioChatExchangeOutcome.Failure(aiFailureDisplayMessage(context, result.exceptionOrNull()))
    }
}
