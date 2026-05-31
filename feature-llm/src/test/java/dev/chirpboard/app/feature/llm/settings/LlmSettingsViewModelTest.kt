package dev.chirpboard.app.feature.llm.settings

import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.feature.llm.client.LlmClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LlmSettingsViewModelTest {
    private lateinit var preferences: LlmSettingsStore
    private lateinit var backupManager: LlmApiKeyBackupManager
    private lateinit var llmClient: LlmClient
    private lateinit var viewModel: LlmSettingsViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        preferences = mockk(relaxUnitFun = true)
        coEvery { preferences.getLlmEnabled() } returns true
        every { preferences.getActiveProvider() } returns LlmProvider.GEMINI
        every { preferences.getModelFor(LlmProvider.GEMINI) } returns DEFAULT_GEMINI_MODEL
        every { preferences.fetchApiKeyFor(LlmProvider.GEMINI) } returns "initial-key"
        every { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returns true
        every { preferences.isSecureStorageAvailable() } returns true
        every { preferences.countConfiguredApiKeys() } returns 1
        coEvery { preferences.getAutoTitle() } returns false
        coEvery { preferences.getAutoSummary() } returns true
        backupManager = mockk(relaxed = true)
        llmClient = mockk()
        viewModel = LlmSettingsViewModel(preferences, backupManager, llmClient, SavedStateHandle())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialization loads preferences into uiState`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.llmEnabled)
            assertEquals("initial-key", state.apiKey)
            assertTrue(state.isKeyConfigured)
            assertFalse(state.autoTitle)
            assertFalse(state.autoTitle)
            assertTrue(state.autoSummary)
        }

    @Test
    fun `updateApiKey updates local state but does not save`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.updateApiKey("new-key")

            val state = viewModel.uiState.value
            assertEquals("new-key", state.apiKey)
            assertTrue(state.isKeyConfigured)

            verify(exactly = 0) { preferences.setApiKeyFor(any(), any()) }
        }

    @Test
    fun `initialization does not clobber in-progress api key input`() =
        runTest {
            val savedStateHandle = SavedStateHandle()
            savedStateHandle["apiKeyInput_gemini"] = "typed-before-init"
            viewModel = LlmSettingsViewModel(preferences, backupManager, llmClient, savedStateHandle)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("typed-before-init", viewModel.uiState.value.apiKey)
        }

    @Test
    fun `saveApiKey saves current key to preferences`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            every { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returnsMany listOf(true, true)
            viewModel.updateApiKey("saved-key")
            viewModel.saveApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { preferences.setApiKeyFor(LlmProvider.GEMINI, "saved-key") }
        }

    @Test
    fun `clearApiKey clears preferences and local state`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.clearApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { preferences.clearApiKeyFor(LlmProvider.GEMINI) }
            assertEquals("", viewModel.uiState.value.apiKey)
        }

    @Test
    fun `testConnection with blank key returns error immediately`() =
        runTest {
            viewModel.updateApiKey("   ")
            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            val result = viewModel.uiState.value.connectionTestResult
            assertTrue(result is LlmSettingsViewModel.ConnectionTestResult.Error)
            assertEquals("API key not configured", (result as LlmSettingsViewModel.ConnectionTestResult.Error).message)
        }

    @Test
    fun `testConnection success`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            every { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returnsMany listOf(true, true, true)
            viewModel.updateApiKey("valid-key")
            coEvery { llmClient.process(any<String>(), any<String>()) } returns Result.success("OK")

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { preferences.setApiKeyFor(LlmProvider.GEMINI, "valid-key") }
            val result = viewModel.uiState.value.connectionTestResult
            assertTrue(result is LlmSettingsViewModel.ConnectionTestResult.Success)
        }

    @Test
    fun `testConnection failure`() =
        runTest {
            viewModel.updateApiKey("valid-key")
            coEvery { llmClient.process(any<String>(), any<String>()) } returns Result.failure(Exception("Network Error"))

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            val result = viewModel.uiState.value.connectionTestResult
            assertTrue(result is LlmSettingsViewModel.ConnectionTestResult.Error)
            assertEquals("Network Error", (result as LlmSettingsViewModel.ConnectionTestResult.Error).message)
        }

    @Test
    fun `dismissTestResult clears result`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            every { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returnsMany listOf(true, true, true)
            viewModel.updateApiKey("valid-key")
            coEvery { llmClient.process(any<String>(), any<String>()) } returns Result.success("OK")
            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.dismissTestResult()
            assertNull(viewModel.uiState.value.connectionTestResult)
        }
}
