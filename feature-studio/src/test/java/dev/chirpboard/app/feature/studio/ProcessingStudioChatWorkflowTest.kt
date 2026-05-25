package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.feature.llm.model.ChatMessage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProcessingStudioChatWorkflowTest {
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
}
