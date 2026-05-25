package dev.chirpboard.app.feature.recording.ui.profile

import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.data.repository.ProfileRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditorViewModelTest {
    private lateinit var profileRepository: ProfileRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        profileRepository = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `save includes quick start pin selection`() = runTest {
        val viewModel = ProfileEditorViewModel(profileRepository, SavedStateHandle())

        viewModel.updateName("Pinned")
        viewModel.updateQuickStartPinned(true)
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            profileRepository.createProfile(
                match<ProfileRepository.CreateProfileRequest> {
                    it.name == "Pinned" && it.quickStartPinned
                },
            )
        }
    }
}