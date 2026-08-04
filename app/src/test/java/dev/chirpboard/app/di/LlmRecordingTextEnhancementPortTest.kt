package dev.chirpboard.app.di

import dev.chirpboard.app.cloud.VertexTextGenerationClient
import dev.chirpboard.app.core.llm.GOOGLE_CLOUD_VERTEX_PROVIDER_ID
import dev.chirpboard.app.core.llm.RecordingTextEnhancementContext
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmRecordingTextEnhancementPortTest {
    private lateinit var llmPreferences: LlmPreferences
    private lateinit var vertexClient: VertexTextGenerationClient
    private lateinit var port: LlmRecordingTextEnhancementPort

    @Before
    fun setup() {
        llmPreferences = mockk(relaxed = true)
        vertexClient = mockk(relaxed = true)
        port =
            LlmRecordingTextEnhancementPort(
                textProcessor = mockk<TextProcessor>(relaxed = true),
                modeRepository = mockk<ProcessingModeRepository>(relaxed = true),
                llmClient = mockk<LlmClient>(relaxed = true),
                llmPreferences = llmPreferences,
                vertexTextGenerationClient = vertexClient,
            )
    }

    @Test
    fun disabledMasterSwitch_keepsVertexUnavailable() =
        runTest {
            coEvery { llmPreferences.getLlmEnabled() } returns false
            coEvery { vertexClient.isConfigured() } returns true

            assertFalse(port.isEnhancementEnabled())
            val available = port.isEnhancementAvailable(GOOGLE_CLOUD_VERTEX_PROVIDER_ID)

            assertFalse(available)
            coVerify(exactly = 0) { vertexClient.isConfigured() }
        }

    @Test
    fun disabledMasterSwitch_blocksAQueuedVertexCall() =
        runTest {
            coEvery { llmPreferences.getLlmEnabled() } returns false

            val result =
                port.processResolved(
                    context =
                        RecordingTextEnhancementContext(
                            text = "private transcript",
                            providerId = GOOGLE_CLOUD_VERTEX_PROVIDER_ID,
                            modelId = null,
                            recordingId = "a9026856-c916-4ef0-a630-6f652c83c200",
                        ),
                    prompt = "Clean this up",
                    fallbackProcessingModeId = "proofread",
                )

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { vertexClient.generate(any(), any(), any(), any()) }
        }
}
