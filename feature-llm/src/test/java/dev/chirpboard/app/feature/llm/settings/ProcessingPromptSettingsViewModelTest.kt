package dev.chirpboard.app.feature.llm.settings

import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.feature.llm.R
import dev.chirpboard.app.feature.llm.model.ProcessingModeListItem
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingPromptSettingsViewModelTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private val appContext =
        mockk<android.content.Context> {
            every { getString(R.string.llm_prompt_delete_failed) } returns "Couldn't delete the preset. Try again."
            every { getString(R.string.llm_prompt_default_mode_failed) } returns
                "Couldn't change the default mode. Try again."
        }

    private lateinit var modeRepository: ProcessingModeRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        modeRepository = mockk(relaxUnitFun = true)
        every { modeRepository.promptPresets } returns flowOf(emptyList())
        every { modeRepository.selectableModes } returns flowOf(listOf(ProcessingModeListItem("smart", "Smart")))
        every { modeRepository.defaultModeId } returns flowOf("smart")
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    /** stateIn uses WhileSubscribed, so a live collector is needed to see recomputed state. */
    private fun TestScope.subscribedViewModel(): ProcessingPromptSettingsViewModel {
        val viewModel = ProcessingPromptSettingsViewModel(appContext, modeRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `a failed default-mode write surfaces a status message`() =
        runTest {
            coEvery { modeRepository.setModeById(any()) } throws java.io.IOException("store unreadable")
            val viewModel = subscribedViewModel()

            viewModel.setDefaultMode("proofread")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Couldn't change the default mode. Try again.", viewModel.uiState.value.statusMessage)

            viewModel.dismissStatusMessage()
            testDispatcher.scheduler.advanceUntilIdle()
            assertNull(viewModel.uiState.value.statusMessage)
        }

    @Test
    fun `a failed delete surfaces a status message`() =
        runTest {
            coEvery { modeRepository.deleteCustomPreset(any()) } throws java.io.IOException("store unreadable")
            val viewModel = subscribedViewModel()

            viewModel.deleteCustomPreset("user_1")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Couldn't delete the preset. Try again.", viewModel.uiState.value.statusMessage)
        }

    @Test
    fun `a successful default-mode write leaves no status message`() =
        runTest {
            val viewModel = subscribedViewModel()

            viewModel.setDefaultMode("proofread")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.uiState.value.statusMessage)
        }
}
