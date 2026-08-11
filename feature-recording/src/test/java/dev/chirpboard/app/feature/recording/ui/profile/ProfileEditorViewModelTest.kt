package dev.chirpboard.app.feature.recording.ui.profile

import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.data.repository.ProfileRepository
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditorViewModelTest {
    private val appContext = mockk<android.content.Context>(relaxed = true)
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
        val viewModel = ProfileEditorViewModel(appContext, profileRepository, SavedStateHandle())

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

    @Test
    fun `saving an edit of a deleted profile reports an error instead of success`() = runTest {
        val profileId = java.util.UUID.randomUUID()
        coEvery { profileRepository.getProfile(profileId) } returns null
        every { appContext.getString(any()) } returns "Profile not found"
        val viewModel =
            ProfileEditorViewModel(
                appContext,
                profileRepository,
                SavedStateHandle(mapOf("profileId" to profileId.toString())),
            )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateName("Edited")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        org.junit.Assert.assertFalse(state.isSaved)
        org.junit.Assert.assertEquals("Profile not found", state.error)
        coVerify(exactly = 0) { profileRepository.update(any()) }
    }
}