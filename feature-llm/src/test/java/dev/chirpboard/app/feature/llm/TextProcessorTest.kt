package dev.chirpboard.app.feature.llm

import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextProcessorTest {
    private lateinit var llmClient: LlmClient
    private lateinit var textProcessor: TextProcessor

    @Before
    fun setup() {
        llmClient = mockk()
        textProcessor = TextProcessor(llmClient)
    }

    @Test
    fun `process with Formal mode uses formal prompt`() =
        runTest {
            coEvery { llmClient.process(any(), any()) } returns Result.success("Success")

            val mode = ProcessingMode.Formal
            textProcessor.process("test", mode)

            coVerify { llmClient.process("test", mode.prompt!!) }
        }

    @Test
    fun `process with Custom mode uses custom prompt`() =
        runTest {
            coEvery { llmClient.process(any(), any()) } returns Result.success("Success")

            val mode = ProcessingMode.Custom("My custom prompt")
            textProcessor.process("test", mode)

            coVerify { llmClient.process("test", "My custom prompt") }
        }

    @Test
    fun `process with Smart mode detects email`() =
        runTest {
            coEvery { llmClient.process(any(), any()) } returns Result.success("Success")

            val mode = ProcessingMode.Smart
            val text = "Dear John, please find the attachment."
            textProcessor.process(text, mode)

            coVerify { llmClient.process(text, ProcessingMode.Email.prompt!!) }
        }

    @Test
    fun `process with Smart mode detects code`() =
        runTest {
            coEvery { llmClient.process(any(), any()) } returns Result.success("Success")

            val mode = ProcessingMode.Smart
            val text = "public static void main(String[] args) {}"
            textProcessor.process(text, mode)

            coVerify { llmClient.process(text, ProcessingMode.Code.prompt!!) }
        }
}
