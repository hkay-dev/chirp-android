package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.feature.llm.model.ChatMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProcessingStudioChatWorkflowTest {
    // I18N-08: the failure copy moved to string resources; tests stub the lookups.
    private val context =
        mockk<android.content.Context> {
            every { getString(R.string.rec_ai_failure_network) } returns
                "Couldn't reach the AI service. Check your internet connection and try again."
            every { getString(R.string.rec_ai_failure_generic) } returns
                "The AI request failed. Try again, or check your AI Processing settings."
        }

    @Test
    fun `createStudioChatMessage sets user flag`() {
        val message = createStudioChatMessage("Hello", isFromUser = true)

        assertEquals("Hello", message.text)
        assertEquals(true, message.isFromUser)
    }

    @Test
    fun `completeStudioChatExchange returns the ai reply`() = runTest {
        val llmClient = mockk<dev.chirpboard.app.feature.llm.client.LlmClient>()
        val userMessage = createStudioChatMessage("Question", isFromUser = true)
        coEvery {
            llmClient.generateChatResponse("transcript", listOf(userMessage))
        } returns Result.success("Answer")

        val outcome =
            completeStudioChatExchange(
                context = context,
                llmClient = llmClient,
                transcriptText = "transcript",
                history = persistentListOf(userMessage),
            )

        val reply = outcome as StudioChatExchangeOutcome.Reply
        assertEquals("Answer", reply.message.text)
        assertFalse(reply.message.isFromUser)
    }

    @Test
    fun `completeStudioChatExchange caps the history sent to the model`() = runTest {
        val llmClient = mockk<dev.chirpboard.app.feature.llm.client.LlmClient>()
        val history =
            (1..MAX_STUDIO_CHAT_HISTORY_MESSAGES + 5)
                .map { createStudioChatMessage("m$it", isFromUser = it % 2 == 1) }
                .toImmutableList()
        coEvery {
            llmClient.generateChatResponse("transcript", history.takeLast(MAX_STUDIO_CHAT_HISTORY_MESSAGES))
        } returns Result.success("Answer")

        val outcome =
            completeStudioChatExchange(
                context = context,
                llmClient = llmClient,
                transcriptText = "transcript",
                history = history,
            )

        assertEquals("Answer", (outcome as StudioChatExchangeOutcome.Reply).message.text)
    }

    @Test
    fun `completeStudioChatExchange surfaces actionable copy on failure`() = runTest {
        // ERR-11/I18N-05: failures classify into actionable copy instead of a generic apology,
        // and come back as a Failure (snackbar) rather than a fake assistant bubble that would
        // replay into later requests as conversation history.
        val llmClient = mockk<dev.chirpboard.app.feature.llm.client.LlmClient>()
        val userMessage = createStudioChatMessage("Question", isFromUser = true)
        coEvery {
            llmClient.generateChatResponse("transcript", listOf(userMessage))
        } returns Result.failure(java.io.IOException("Unable to resolve host"))

        val outcome =
            completeStudioChatExchange(
                context = context,
                llmClient = llmClient,
                transcriptText = "transcript",
                history = persistentListOf(userMessage),
            )

        assertEquals(
            "Couldn't reach the AI service. Check your internet connection and try again.",
            (outcome as StudioChatExchangeOutcome.Failure).displayMessage,
        )
    }

    @Test
    fun `aiFailureDisplayMessage classifies network vs other failures`() {
        assertEquals(
            "Couldn't reach the AI service. Check your internet connection and try again.",
            aiFailureDisplayMessage(context, java.io.IOException("timeout")),
        )
        assertEquals(
            "The AI request failed. Try again, or check your AI Processing settings.",
            aiFailureDisplayMessage(context, IllegalStateException("HTTP 429")),
        )
        assertEquals(
            "The AI request failed. Try again, or check your AI Processing settings.",
            aiFailureDisplayMessage(context, null),
        )
    }
}
