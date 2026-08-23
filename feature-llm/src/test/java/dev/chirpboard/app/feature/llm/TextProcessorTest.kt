package dev.chirpboard.app.feature.llm

import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.TranscriptLlmContext
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.model.ProcessingModeDefaults
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextProcessorTest {
    private lateinit var llmClient: LlmClient
    private lateinit var modeRepository: ProcessingModeRepository
    private lateinit var textProcessor: TextProcessor

    @Before
    fun setup() {
        llmClient = mockk()
        modeRepository = mockk()
        textProcessor = TextProcessor(llmClient, modeRepository)
    }

    @Test
    fun `process with Formal mode uses repository prompt`() =
        runTest {
            coEvery { modeRepository.getPrompt("formal") } returns "formal prompt"
            coEvery { llmClient.process(any<TranscriptLlmContext>(), any()) } returns Result.success("Success")

            textProcessor.process(TranscriptLlmContext("test"), ProcessingMode.Formal)

            coVerify {
                llmClient.process(
                    any<TranscriptLlmContext>(),
                    match { it.contains("LOSSLESS TRANSCRIPT MANDATE") && it.endsWith("formal prompt") },
                )
            }
        }

    @Test
    fun `process with Custom mode uses custom prompt`() =
        runTest {
            coEvery { llmClient.process(any<TranscriptLlmContext>(), any()) } returns Result.success("Success")

            val mode = ProcessingMode.Custom("My custom prompt")
            textProcessor.process(TranscriptLlmContext("test"), mode)

            coVerify {
                llmClient.process(
                    any<TranscriptLlmContext>(),
                    match { it.contains("LOSSLESS TRANSCRIPT MANDATE") && it.endsWith("My custom prompt") },
                )
            }
        }

    @Test
    fun `process with Smart mode detects email`() =
        runTest {
            coEvery { modeRepository.getPrompt("email") } returns ProcessingModeDefaults.defaultPrompt("email")
            coEvery { llmClient.process(any<TranscriptLlmContext>(), any()) } returns Result.success("Success")

            val mode = ProcessingMode.Smart
            val text = "Dear John, please find the attachment."
            textProcessor.process(TranscriptLlmContext(text), mode)

            coVerify {
                llmClient.process(
                    any<TranscriptLlmContext>(),
                    match { it.contains("LOSSLESS TRANSCRIPT MANDATE") && it.endsWith(ProcessingMode.Email.prompt!!) },
                )
            }
        }

    @Test
    fun `smart detection keeps email true positives`() {
        listOf(
            "Dear John, please find the attachment.",
            "Hi Bob, thanks for the update.",
            "Hello team, following up on yesterday. Kind regards, Sam.",
            "Sincerely, Alex",
        ).forEach { text ->
            assertEquals(text, ProcessingMode.Email, textProcessor.detectContentType(text))
        }
    }

    @Test
    fun `smart detection keeps code true positives`() {
        listOf(
            "public static void main(String[] args) {}",
            "const total = items.filter(x => x.price != null)",
            "def parse(payload): return payload",
        ).forEach { text ->
            assertEquals(text, ProcessingMode.Code, textProcessor.detectContentType(text))
        }
    }

    @Test
    fun `smart detection ignores email words hidden inside other words`() {
        listOf(
            // "hi " used to match inside "sushi ".
            "We had sushi for lunch and it was excellent.",
            // A lone polite word is not an email.
            "Thanks, that machine finally stopped rebooting.",
            "Hello there.",
        ).forEach { text ->
            assertEquals(text, ProcessingMode.Formal, textProcessor.detectContentType(text))
        }
    }

    @Test
    fun `smart detection ignores stray punctuation and single code words`() {
        listOf(
            // A single parenthesis used to be enough to pick Code.
            "The quarterly numbers (finally) came in above plan.",
            // "class " used to match ordinary prose.
            "The class was cancelled because the room was double booked.",
            "Let me know if the return policy changed.",
        ).forEach { text ->
            assertEquals(text, ProcessingMode.Formal, textProcessor.detectContentType(text))
        }
    }

    @Test
    fun `process with Smart mode detects code`() =
        runTest {
            coEvery { modeRepository.getPrompt("code") } returns ProcessingModeDefaults.defaultPrompt("code")
            coEvery { llmClient.process(any<TranscriptLlmContext>(), any()) } returns Result.success("Success")

            val mode = ProcessingMode.Smart
            val text = "public static void main(String[] args) {}"
            textProcessor.process(TranscriptLlmContext(text), mode)

            coVerify {
                llmClient.process(
                    any<TranscriptLlmContext>(),
                    match { it.contains("LOSSLESS TRANSCRIPT MANDATE") && it.endsWith(ProcessingMode.Code.prompt!!) },
                )
            }
        }
}
