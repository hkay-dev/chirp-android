package dev.chirpboard.app.ui.settings

import dev.chirpboard.app.core.preferences.KeyboardPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardSettingsViewModelTest {
    private lateinit var keyboardPreferences: KeyboardPreferences
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        keyboardPreferences = mockk()

        every { keyboardPreferences.saveKeyboardRecordings } returns MutableStateFlow(true)
        every { keyboardPreferences.llmEnabled } returns MutableStateFlow(false)
        every { keyboardPreferences.defaultProcessingMode } returns MutableStateFlow("custom_mode")
        every { keyboardPreferences.microphoneGain } returns MutableStateFlow(2.0f)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initializes with preferences values`() =
        runTest {
            val viewModel = KeyboardSettingsViewModel(keyboardPreferences)
            val state = viewModel.uiState.value

            assertEquals(true, state.saveKeyboardRecordings)
            assertEquals(false, state.llmEnabled)
            assertEquals("custom_mode", state.defaultProcessingMode)
            assertEquals(2.0f, state.microphoneGain)
        }

    @Test
    fun `toggleSaveRecordings updates preferences`() =
        runTest {
            coEvery { keyboardPreferences.setSaveKeyboardRecordings(any()) } returns Unit
            val viewModel = KeyboardSettingsViewModel(keyboardPreferences)

            viewModel.toggleSaveRecordings()

            coVerify { keyboardPreferences.setSaveKeyboardRecordings(false) } // Initially true
        }

    @Test
    fun `toggleLlmEnabled updates preferences`() =
        runTest {
            coEvery { keyboardPreferences.setLlmEnabled(any()) } returns Unit
            val viewModel = KeyboardSettingsViewModel(keyboardPreferences)

            viewModel.toggleLlmEnabled()

            coVerify { keyboardPreferences.setLlmEnabled(true) } // Initially false
        }

    @Test
    fun `setProcessingMode updates preferences`() =
        runTest {
            coEvery { keyboardPreferences.setDefaultProcessingMode(any()) } returns Unit
            val viewModel = KeyboardSettingsViewModel(keyboardPreferences)

            viewModel.setProcessingMode("new_mode")

            coVerify { keyboardPreferences.setDefaultProcessingMode("new_mode") }
        }
}
