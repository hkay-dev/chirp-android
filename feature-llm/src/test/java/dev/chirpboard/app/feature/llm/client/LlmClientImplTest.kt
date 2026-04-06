package dev.chirpboard.app.feature.llm.client

import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

class LlmClientImplTest {
    private lateinit var preferences: LlmPreferences
    private lateinit var client: LlmClientImpl

    @Before
    fun setup() {
        preferences = mockk()
        client = LlmClientImpl(preferences)
    }

    @Ignore("Network dependent")
    @Test
    fun `executeRequest fails when api key is blank`() = runTest {
        coEvery { preferences.apiKey } returns flowOf("")
        coEvery { preferences.getModelName() } returns "gemini-test"

        val result = client.generateTitle("test")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("API key not configured") == true)
    }
    
    @Ignore("Network dependent")
    @Test
    fun `executeRequest fails on network error`() = runTest {
        coEvery { preferences.apiKey } returns flowOf("fake-key")
        coEvery { preferences.getModelName() } returns "gemini-test"

        val result = client.generateSummary("test")
        assertTrue(result.isFailure)
    }
    
    @Ignore("Network dependent")
    @Test
    fun `process formats system prompt correctly and fails on network`() = runTest {
        coEvery { preferences.apiKey } returns flowOf("fake-key")
        coEvery { preferences.getModelName() } returns "gemini-test"

        val result = client.process("text", "system")
        assertTrue(result.isFailure)
    }
}
