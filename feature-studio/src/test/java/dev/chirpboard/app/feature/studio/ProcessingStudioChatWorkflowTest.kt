package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.feature.llm.model.ChatMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
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
    fun `completeStudioChatExchange appends ai response`() = runTest {
        val llmClient = mockk<dev.chirpboard.app.feature.llm.client.LlmClient>()
        val userMessage = createStudioChatMessage("Question", isFromUser = true)
        coEvery {
            llmClient.generateChatResponse("transcript", persistentListOf(userMessage))
        } returns Result.success("Answer")

        val result =
            completeStudioChatExchange(
                context = context,
                llmClient = llmClient,
                transcriptText = "transcript",
                messagesWithUser = persistentListOf(userMessage),
            )

        assertFalse(result.isTyping)
        assertEquals(2, result.messages.size)
        assertEquals("Question", result.messages[0].text)
        assertEquals("Answer", result.messages[1].text)
        assertFalse(result.messages[1].isFromUser)
    }

    @Test
    fun `completeStudioChatExchange surfaces actionable copy on failure`() = runTest {
        // ERR-11/I18N-05: failures classify into actionable copy instead of a generic apology
        // (and never leak raw exception text into the chat bubble).
        val llmClient = mockk<dev.chirpboard.app.feature.llm.client.LlmClient>()
        val userMessage = createStudioChatMessage("Question", isFromUser = true)
        coEvery {
            llmClient.generateChatResponse("transcript", persistentListOf(userMessage))
        } returns Result.failure(java.io.IOException("Unable to resolve host"))

        val result =
            completeStudioChatExchange(
                context = context,
                llmClient = llmClient,
                transcriptText = "transcript",
                messagesWithUser = persistentListOf(userMessage),
            )

        assertEquals(
            "Couldn't reach the AI service. Check your internet connection and try again.",
            result.messages[1].text,
        )
        assertFalse(result.messages[1].isFromUser)
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
