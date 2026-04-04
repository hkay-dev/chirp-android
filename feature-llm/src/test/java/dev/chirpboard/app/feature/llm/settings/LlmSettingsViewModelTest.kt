package dev.chirpboard.app.feature.llm.settings

import dev.chirpboard.app.feature.llm.client.LlmClient
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LlmSettingsViewModelTest {
    private lateinit var preferences: LlmPreferences
    private lateinit var llmClient: LlmClient
    private lateinit var viewModel: LlmSettingsViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        preferences = mockk(relaxed = true) {
            coEvery { llmEnabled } returns flowOf(true)
            coEvery { apiKey } returns flowOf("initial-key")
            coEvery { autoTitle } returns flowOf(false)
            coEvery { autoSummary } returns flowOf(true)
        }
        llmClient = mockk()
        viewModel = LlmSettingsViewModel(preferences, llmClient)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialization loads preferences into uiState`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertTrue(state.llmEnabled)
        assertEquals("initial-key", state.apiKey)
        assertTrue(state.isKeyConfigured)
        assertFalse(state.autoTitle)
        assertTrue(state.autoSummary)
    }

    @Test
    fun `updateApiKey updates local state but does not save`() = runTest {
        viewModel.updateApiKey("new-key")
        
        val state = viewModel.uiState.value
        assertEquals("new-key", state.apiKey)
        assertTrue(state.isKeyConfigured)
        
        coVerify(exactly = 0) { preferences.setApiKey(any()) }
    }

    @Test
    fun `saveApiKey saves current key to preferences`() = runTest {
        viewModel.updateApiKey("saved-key")
        viewModel.saveApiKey()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { preferences.setApiKey("saved-key") }
    }

    @Test
    fun `clearApiKey clears preferences and local state`() = runTest {
        viewModel.clearApiKey()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { preferences.clearApiKey() }
        assertEquals("", viewModel.uiState.value.apiKey)
    }

    @Test
    fun `testConnection with blank key returns error immediately`() = runTest {
        viewModel.updateApiKey("   ")
        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()
        
        val result = viewModel.uiState.value.connectionTestResult
        assertTrue(result is LlmSettingsViewModel.ConnectionTestResult.Error)
        assertEquals("API key not configured", (result as LlmSettingsViewModel.ConnectionTestResult.Error).message)
    }

    @Test
    fun `testConnection success`() = runTest {
        viewModel.updateApiKey("valid-key")
        coEvery { llmClient.process(any(), any()) } returns Result.success("OK")
        
        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { preferences.setApiKey("valid-key") }
        val result = viewModel.uiState.value.connectionTestResult
        assertTrue(result is LlmSettingsViewModel.ConnectionTestResult.Success)
    }

    @Test
    fun `testConnection failure`() = runTest {
        viewModel.updateApiKey("valid-key")
        coEvery { llmClient.process(any(), any()) } returns Result.failure(Exception("Network Error"))
        
        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()
        
        val result = viewModel.uiState.value.connectionTestResult
        assertTrue(result is LlmSettingsViewModel.ConnectionTestResult.Error)
        assertEquals("Network Error", (result as LlmSettingsViewModel.ConnectionTestResult.Error).message)
    }

    @Test
    fun `dismissTestResult clears result`() = runTest {
        viewModel.updateApiKey("valid-key")
        coEvery { llmClient.process(any(), any()) } returns Result.success("OK")
        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.dismissTestResult()
        assertNull(viewModel.uiState.value.connectionTestResult)
    }
}
