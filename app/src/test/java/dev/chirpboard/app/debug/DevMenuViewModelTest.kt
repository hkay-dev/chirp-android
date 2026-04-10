package dev.chirpboard.app.debug

import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevMenuViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test viewmodel initialization`() =
        runTest {
            val llmPreferences = mockk<LlmPreferences>(relaxed = true)
            val recordingRepository = mockk<RecordingRepository>(relaxed = true)
            val tagRepository = mockk<TagRepository>(relaxed = true)
            val profileRepository = mockk<ProfileRepository>(relaxed = true)

            val viewModel =
                DevMenuViewModel(
                    llmPreferences,
                    recordingRepository,
                    tagRepository,
                    profileRepository,
                )
            assertNotNull(viewModel)
        }
}
