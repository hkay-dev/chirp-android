package dev.chirpboard.app.feature.recording.ui

import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RecordViewModelTest {
    private lateinit var recordingManager: RecordingManager
    private lateinit var recordingStateManager: RecordingStateManager
    private lateinit var profileRepository: ProfileRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var recoveryStore: RecordingRecoveryStore
    private lateinit var viewModel: RecordViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        recordingManager = mockk(relaxed = true)
        recordingStateManager =
            mockk(relaxed = true) {
                every { state } returns MutableStateFlow(RecordingState.Idle)
                every { waveformBuffer } returns dev.chirpboard.app.core.recording.WaveformBuffer(1000)
                every { amplitudeFlow } returns MutableStateFlow(0f)
                every { amplitudeSampleCountFlow } returns MutableStateFlow(0L)
                every { lastCompletedRecordingId } returns MutableStateFlow(null)
            }
        profileRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        every { tagRepository.getAllTags() } returns emptyFlow()
        recoveryStore = mockk(relaxed = true)
        every { recoveryStore.pendingSessions } returns MutableStateFlow(emptyList())
        every { recoveryStore.actionablePendingSessions } returns MutableStateFlow(emptyList())
        coEvery { recoveryStore.refresh() } returns Unit

        viewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                savedStateHandle = SavedStateHandle(),
            )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selected profile is resolved for the recording session`() = runTest(testDispatcher) {
        val profileId = UUID.randomUUID()
        val profile = Profile(id = profileId, name = "Meeting", icon = "🎤")
        coEvery { profileRepository.getProfile(profileId) } returns profile

        val recordViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                savedStateHandle = SavedStateHandle(mapOf("profileId" to profileId.toString())),
            )

        advanceUntilIdle()

        assertEquals(profileId, recordViewModel.activeProfile.value?.id)
        assertEquals("Meeting", recordViewModel.activeProfile.value?.name)
        assertEquals("🎤", recordViewModel.activeProfile.value?.icon)
        assertEquals(true, recordViewModel.isProfileHandoffResolved.value)
        assertNull(recordViewModel.entryMessage.value)

        recordViewModel.startRecording()

        verify { recordingManager.startRecording(RecordingOrigin.APP, profileId) }
    }

    @Test
    fun `stopRecordingWithHandoff returns active recording id and stops capture`() = runTest(testDispatcher) {
        val recordingId = UUID.randomUUID()
        every { recordingStateManager.state } returns
            MutableStateFlow(
                RecordingState.Recording(
                    origin = RecordingOrigin.APP,
                    profileId = null,
                    startTimeMs = 0L,
                    audioFilePath = "path",
                    recordingId = recordingId,
                ),
            )

        val handoffViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                savedStateHandle = SavedStateHandle(),
            )

        val handoffId = handoffViewModel.stopRecordingWithHandoff()

        assertEquals(recordingId, handoffId)
        verify { recordingManager.stopRecording() }
    }

    @Test
    fun `stopRecordingWithHandoff returns null when no active recording id`() = runTest(testDispatcher) {
        every { recordingStateManager.state } returns MutableStateFlow(RecordingState.Starting(RecordingOrigin.APP))

        val handoffId = viewModel.stopRecordingWithHandoff()

        assertNull(handoffId)
        verify(exactly = 0) { recordingManager.stopRecording() }
    }

    @Test
    fun `canHandoffToStudio is false while recording id is unassigned`() = runTest(testDispatcher) {
        every { recordingStateManager.state } returns MutableStateFlow(RecordingState.Starting(RecordingOrigin.APP))

        assertEquals(false, viewModel.canHandoffToStudio())
    }

    @Test
    fun `missing selected profile falls back to no-profile recording`() = runTest(testDispatcher) {
        val profileId = UUID.randomUUID()
        coEvery { profileRepository.getProfile(profileId) } returns null

        val recordViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                savedStateHandle = SavedStateHandle(mapOf("profileId" to profileId.toString())),
            )

        advanceUntilIdle()

        assertNull(recordViewModel.activeProfile.value)
        assertEquals(true, recordViewModel.isProfileHandoffResolved.value)
        assertEquals(
            "Profile no longer exists. Using default recording settings.",
            recordViewModel.entryMessage.value,
        )

        recordViewModel.startRecording()

        verify { recordingManager.startRecording(RecordingOrigin.APP, null) }
    }
}
