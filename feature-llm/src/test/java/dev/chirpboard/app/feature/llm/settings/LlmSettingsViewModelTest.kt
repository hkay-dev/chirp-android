package dev.chirpboard.app.feature.llm.settings

import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.feature.llm.R
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.TranscriptLlmContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
    @get:org.junit.Rule
    val androidLog = MockAndroidLogRule()

    // I18N-08: settings copy moved to resources; the mock resolves the ids the tests assert.
    private val appContext =
        mockk<android.content.Context> {
            every { getString(R.string.llm_error_key_not_configured) } returns "API key not configured"
            every { getString(R.string.llm_error_key_save_failed) } returns "Failed to save API key"
            every { getString(R.string.llm_error_secure_storage_unavailable) } returns
                "Secure storage unavailable on this device"
            every { getString(R.string.llm_error_connection_network) } returns
                "Couldn't reach the provider. Check your internet connection."
            every { getString(R.string.llm_error_connection_rejected) } returns
                "The provider rejected the request. Check your API key and model."
            every { getString(R.string.llm_error_key_blank) } returns "Enter an API key first"
        }
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
        coEvery { preferences.getActiveProvider() } returns LlmProvider.GEMINI
        coEvery { preferences.getModelFor(LlmProvider.GEMINI) } returns DEFAULT_GEMINI_MODEL
        coEvery { preferences.fetchApiKeyFor(LlmProvider.GEMINI) } returns "initial-key"
        coEvery { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returns true
        coEvery { preferences.isSecureStorageAvailable() } returns true
        coEvery { preferences.consumeSecureStorageResetNotice() } returns false
        coEvery { preferences.countConfiguredApiKeys() } returns 1
        coEvery { preferences.getAutoTitle() } returns false
        coEvery { preferences.getAutoSummary() } returns true
        backupManager = mockk(relaxed = true)
        llmClient = mockk()
        viewModel = LlmSettingsViewModel(appContext, preferences, backupManager, llmClient, SavedStateHandle())
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

            coVerify(exactly = 0) { preferences.setApiKeyFor(any(), any()) }
        }

    @Test
    fun `initialization does not clobber in-progress api key input`() =
        runTest {
            val savedStateHandle = SavedStateHandle()
            savedStateHandle["apiKeyInput_gemini"] = "typed-before-init"
            viewModel = LlmSettingsViewModel(appContext, preferences, backupManager, llmClient, savedStateHandle)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("typed-before-init", viewModel.uiState.value.apiKey)
        }

    @Test
    fun `secure storage reset notice surfaces once and is dismissible`() =
        runTest {
            // SEC-2: self-heal wiped the store -> one-shot notice for the user.
            coEvery { preferences.consumeSecureStorageResetNotice() } returns true
            viewModel = LlmSettingsViewModel(appContext, preferences, backupManager, llmClient, SavedStateHandle())
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.secureStorageWasReset)

            viewModel.dismissSecureStorageResetNotice()
            assertFalse(viewModel.uiState.value.secureStorageWasReset)
        }

    @Test
    fun `saveApiKey saves current key to preferences`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            coEvery { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returnsMany listOf(true, true)
            viewModel.updateApiKey("saved-key")
            viewModel.saveApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { preferences.setApiKeyFor(LlmProvider.GEMINI, "saved-key") }
        }

    @Test
    fun `saveApiKey with blank input shows an error and saves nothing`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.updateApiKey("   ")
            viewModel.saveApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            val result = viewModel.uiState.value.connectionTestResult
            assertTrue(result is LlmSettingsViewModel.ConnectionTestResult.Error)
            assertEquals("Enter an API key first", (result as LlmSettingsViewModel.ConnectionTestResult.Error).message)
            coVerify(exactly = 0) { preferences.setApiKeyFor(any(), any()) }
        }

    @Test
    fun `clearApiKey clears preferences and local state`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.clearApiKey()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { preferences.clearApiKeyFor(LlmProvider.GEMINI) }
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
            coEvery { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returnsMany listOf(true, true, true)
            viewModel.updateApiKey("valid-key")
            coEvery { llmClient.process(any<TranscriptLlmContext>(), any<String>()) } returns Result.success("OK")

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { preferences.setApiKeyFor(LlmProvider.GEMINI, "valid-key") }
            val result = viewModel.uiState.value.connectionTestResult
            assertTrue(result is LlmSettingsViewModel.ConnectionTestResult.Success)
        }

    @Test
    fun `testConnection failure`() =
        runTest {
            viewModel.updateApiKey("valid-key")
            // I18N-05: raw client errors are no longer surfaced; the copy is classified.
            coEvery { llmClient.process(any<TranscriptLlmContext>(), any<String>()) } returns
                Result.failure(java.io.IOException("Unable to resolve host"))

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            val result = viewModel.uiState.value.connectionTestResult
            assertTrue(result is LlmSettingsViewModel.ConnectionTestResult.Error)
            assertEquals(
                "Couldn't reach the provider. Check your internet connection.",
                (result as LlmSettingsViewModel.ConnectionTestResult.Error).message,
            )
        }

    @Test
    fun `testConnection failure restores the previously stored key`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.updateApiKey("bad-key")
            coEvery { llmClient.process(any<TranscriptLlmContext>(), any<String>()) } returns
                Result.failure(java.io.IOException("Unable to resolve host"))

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            // The draft was written for the test, then rolled back to the stored key.
            coVerify { preferences.setApiKeyFor(LlmProvider.GEMINI, "bad-key") }
            coVerify { preferences.setApiKeyFor(LlmProvider.GEMINI, "initial-key") }
        }

    @Test
    fun `testConnection failure clears the key when none was stored before`() =
        runTest {
            coEvery { preferences.fetchApiKeyFor(LlmProvider.GEMINI) } returns null
            coEvery { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returns false
            viewModel = LlmSettingsViewModel(appContext, preferences, backupManager, llmClient, SavedStateHandle())
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.updateApiKey("bad-key")
            // The draft write succeeds, so the post-write verification passes.
            coEvery { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returns true
            coEvery { llmClient.process(any<TranscriptLlmContext>(), any<String>()) } returns
                Result.failure(java.io.IOException("Unable to resolve host"))

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { preferences.clearApiKeyFor(LlmProvider.GEMINI) }
        }

    @Test
    fun `dismissTestResult clears result`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            coEvery { preferences.hasApiKeyFor(LlmProvider.GEMINI) } returnsMany listOf(true, true, true)
            viewModel.updateApiKey("valid-key")
            coEvery { llmClient.process(any<TranscriptLlmContext>(), any<String>()) } returns Result.success("OK")
            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.dismissTestResult()
            assertNull(viewModel.uiState.value.connectionTestResult)
        }
}
