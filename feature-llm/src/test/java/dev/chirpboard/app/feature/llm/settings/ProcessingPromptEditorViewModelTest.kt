package dev.chirpboard.app.feature.llm.settings

import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.feature.llm.R
import dev.chirpboard.app.feature.llm.model.ProcessingPromptPreset
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingPromptEditorViewModelTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private val appContext =
        mockk<android.content.Context> {
            every { getString(R.string.llm_prompt_save_failed) } returns "Couldn't save the preset. Try again."
            every { getString(R.string.llm_prompt_reset_failed) } returns "Couldn't reset the prompt. Try again."
            every { getString(R.string.llm_prompt_delete_failed) } returns "Couldn't delete the preset. Try again."
            every { getString(R.string.llm_prompt_not_found) } returns "Preset not found"
        }

    private val customPreset =
        ProcessingPromptPreset(
            id = "user_existing",
            name = "My Preset",
            prompt = "edited prompt",
            originalPrompt = "original prompt",
            isBuiltIn = false,
            isModified = true,
            canEditPrompt = true,
        )

    private lateinit var modeRepository: ProcessingModeRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        modeRepository = mockk(relaxUnitFun = true)
        every { modeRepository.promptPresets } returns flowOf(listOf(customPreset))
        coEvery { modeRepository.addCustomPreset(any(), any()) } returns "user_new"
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun viewModelFor(presetId: String): ProcessingPromptEditorViewModel =
        ProcessingPromptEditorViewModel(
            appContext,
            SavedStateHandle(mapOf(ProcessingPromptEditorViewModel.PRESET_ID_ARG to presetId)),
            modeRepository,
        )

    @Test
    fun `double-tapping save creates only one custom preset`() =
        runTest {
            val viewModel = viewModelFor(ProcessingPromptEditorViewModel.NEW_PRESET_ID)
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.updateName("Brand new")
            viewModel.updatePrompt("Do the thing")

            // Both taps land before the first coroutine gets to run.
            viewModel.save(onSaved = {})
            viewModel.save(onSaved = {})
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { modeRepository.addCustomPreset("Brand new", "Do the thing") }
        }

    @Test
    fun `isSaving clears once the save finishes`() =
        runTest {
            val viewModel = viewModelFor(ProcessingPromptEditorViewModel.NEW_PRESET_ID)
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.updateName("Brand new")
            viewModel.updatePrompt("Do the thing")

            viewModel.save(onSaved = {})
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSaving)
        }

    @Test
    fun `a failed save re-enables saving so the user can retry`() =
        runTest {
            coEvery { modeRepository.addCustomPreset(any(), any()) } throws IllegalStateException("disk full")
            val viewModel = viewModelFor(ProcessingPromptEditorViewModel.NEW_PRESET_ID)
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.updateName("Brand new")
            viewModel.updatePrompt("Do the thing")

            viewModel.save(onSaved = {})
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Couldn't save the preset. Try again.", viewModel.uiState.value.statusMessage)
            assertFalse(viewModel.uiState.value.isSaving)

            coEvery { modeRepository.addCustomPreset(any(), any()) } returns "user_new"
            viewModel.save(onSaved = {})
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 2) { modeRepository.addCustomPreset("Brand new", "Do the thing") }
        }

    @Test
    fun `a failed reset surfaces a status message instead of crashing`() =
        runTest {
            coEvery { modeRepository.resetPresetPrompt(any()) } throws java.io.IOException("store unreadable")
            val viewModel = viewModelFor(customPreset.id)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.resetToOriginal()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Couldn't reset the prompt. Try again.", viewModel.uiState.value.statusMessage)
        }

    @Test
    fun `a failed delete surfaces a status message and does not navigate away`() =
        runTest {
            coEvery { modeRepository.deleteCustomPreset(any()) } throws java.io.IOException("store unreadable")
            val viewModel = viewModelFor(customPreset.id)
            testDispatcher.scheduler.advanceUntilIdle()

            var deleted = false
            viewModel.delete(onDeleted = { deleted = true })
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(deleted)
            assertEquals("Couldn't delete the preset. Try again.", viewModel.uiState.value.statusMessage)
        }

    @Test
    fun `a successful reset leaves no status message`() =
        runTest {
            val viewModel = viewModelFor(customPreset.id)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.resetToOriginal()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { modeRepository.resetPresetPrompt(customPreset.id) }
            assertNull(viewModel.uiState.value.statusMessage)
        }
}
