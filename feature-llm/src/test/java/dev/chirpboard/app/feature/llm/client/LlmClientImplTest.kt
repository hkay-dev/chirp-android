package dev.chirpboard.app.feature.llm.client

import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmClientImplTest {
    private lateinit var chatService: LlmChatService
    private lateinit var client: LlmClientImpl

    @Before
    fun setup() {
        chatService = mockk()
        client = LlmClientImpl(chatService)
    }

    @Test
    fun `process delegates to chat service`() = runTest {
        coEvery { chatService.completePrompt(any()) } returns Result.success("OK")

        val result = client.process("hello", "system")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `generateChatResponse delegates to chat service`() = runTest {
        coEvery { chatService.completeChat(any(), any()) } returns Result.success("answer")

        val result = client.generateChatResponse("transcript", emptyList())

        assertTrue(result.isSuccess)
    }
}
