package dev.chirpboard.app.feature.llm.client

import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val result = client.process(TranscriptLlmContext("hello"), "system")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `transcript context reuses assembled transcript for phases`() = runTest {
        val context = TranscriptLlmContext("hello")
        coEvery { chatService.completePrompt(any()) } returns Result.success("OK")

        client.process(context, "system")
        client.generateTitle(context)
        client.generateSummary(context)

        // Free-text prompts get the transcript fully delimited: opening tag added with a
        // separating blank line, closing tag appended.
        coVerify { chatService.completePrompt("system\n\n<transcript>\nhello\n</transcript>") }
        coVerify { chatService.completePrompt(match { it.endsWith("Transcript:\nhello") }) }
    }

    @Test
    fun `process keeps built-in prompts ending with an opening transcript tag unchanged`() = runTest {
        val context = TranscriptLlmContext("hello")
        coEvery { chatService.completePrompt(any()) } returns Result.success("OK")

        client.process(context, "Instructions here.\n\n<transcript>\n")

        coVerify { chatService.completePrompt("Instructions here.\n\n<transcript>\nhello\n</transcript>") }
    }

    @Test
    fun `context with runtime routing pins provider and model`() = runTest {
        val context = TranscriptLlmContext("hello", providerId = "anthropic", modelId = "model-x")
        coEvery { chatService.completePrompt(any(), any(), any()) } returns Result.success("OK")

        client.generateTitle(context)

        coVerify { chatService.completePrompt("anthropic", "model-x", match { it.endsWith("Transcript:\nhello") }) }
    }

    @Test
    fun `generateChatResponse delegates to chat service`() = runTest {
        coEvery { chatService.completeChat(any(), any()) } returns Result.success("answer")

        val result = client.generateChatResponse("transcript", emptyList())

        assertTrue(result.isSuccess)
    }

    @Test
    fun `structured outcome prompt carries a valid json schema example and ends with the transcript`() = runTest {
        // PIPE-09: the schema example shown to the model must itself be VALID JSON — escaped
        // quotes (\" from a non-raw string) get echoed back by compliant models and then
        // break Gson parsing of the response.
        val promptSlot = slot<String>()
        coEvery { chatService.completePrompt(capture(promptSlot)) } returns
            Result.success("""{"tasks":["ship it"],"decisions":[],"followUps":[]}""")

        client.generateStructuredOutcomeExtraction("we agreed to ship it")

        val prompt = promptSlot.captured
        assertFalse(prompt.contains("\\\""))
        val schemaExample = prompt.substring(prompt.indexOf('{'), prompt.indexOf('}') + 1)
        val parsedSchema =
            com.google.gson.Gson().fromJson(schemaExample, com.google.gson.JsonObject::class.java)
        assertEquals(setOf("tasks", "decisions", "followUps"), parsedSchema.keySet())
        assertTrue(prompt.endsWith("Transcript:\nwe agreed to ship it"))
    }

    @Test
    fun `structured outcome extraction parses a fenced model response end to end`() = runTest {
        coEvery { chatService.completePrompt(any()) } returns
            Result.success(
                """
                ```json
                {"tasks":["follow up with QA"],"decisions":["launch friday"],"follow_ups":["book retro"]}
                ```
                """.trimIndent(),
            )

        val result = client.generateStructuredOutcomeExtraction("transcript")

        assertEquals(
            StructuredOutcomeExtraction(
                tasks = listOf("follow up with QA"),
                decisions = listOf("launch friday"),
                followUps = listOf("book retro"),
            ),
            result.getOrNull(),
        )
    }

    @Test
    fun `structured outcome extraction propagates chat service failure`() = runTest {
        coEvery { chatService.completePrompt(any()) } returns
            Result.failure(IllegalStateException("no api key"))

        val result = client.generateStructuredOutcomeExtraction("transcript")

        assertTrue(result.isFailure)
        assertEquals("no api key", result.exceptionOrNull()?.message)
    }
}
