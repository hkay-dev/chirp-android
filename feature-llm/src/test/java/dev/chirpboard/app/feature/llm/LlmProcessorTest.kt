package dev.chirpboard.app.feature.llm

import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.feature.llm.client.LlmClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LlmProcessorTest {
    private lateinit var llmClient: LlmClient
    private lateinit var processor: LlmProcessor

    @Before
    fun setup() {
        llmClient = mockk()
        processor = LlmProcessor(llmClient)
    }

    @Test
    fun `process blank transcript returns nulls`() = runTest {
        val result = processor.process("   \n ", RecordingSource.APP)
        assertNull(result.title)
        assertNull(result.summary)
    }

    @Test
    fun `process keyboard source generates summary but not title`() = runTest {
        coEvery { llmClient.generateSummary("Hello world") } returns Result.success("Summary text")
        
        val result = processor.process("Hello world", RecordingSource.KEYBOARD)
        assertNull(result.title)
        assertEquals("Summary text", result.summary)
    }

    @Test
    fun `process app source generates both title and summary`() = runTest {
        coEvery { llmClient.generateTitle("Hello world") } returns Result.success("Title text")
        coEvery { llmClient.generateSummary("Hello world") } returns Result.success("Summary text")
        
        val result = processor.process("Hello world", RecordingSource.APP)
        assertEquals("Title text", result.title)
        assertEquals("Summary text", result.summary)
    }

    @Test
    fun `process handles failure from client`() = runTest {
        coEvery { llmClient.generateTitle("Error text") } returns Result.failure(Exception("Fail"))
        coEvery { llmClient.generateSummary("Error text") } returns Result.failure(Exception("Fail"))
        
        val result = processor.process("Error text", RecordingSource.WIDGET)
        assertNull(result.title)
        assertNull(result.summary)
    }
}
