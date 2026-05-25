package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.feature.recording.service.RecordingService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    private lateinit var context: Context
    private lateinit var recordingStateManager: RecordingStateManager
    private lateinit var profileRepository: ProfileRepository
    private lateinit var viewModel: RecordViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        recordingStateManager =
            mockk(relaxed = true) {
                every { state } returns MutableStateFlow(RecordingState.Idle)
                every { waveformBuffer } returns dev.chirpboard.app.core.recording.WaveformBuffer(1000)
                every { amplitudeFlow } returns MutableStateFlow(0f)
                every { amplitudeSampleCountFlow } returns MutableStateFlow(0L)
                every { lastCompletedRecordingId } returns MutableStateFlow(null)
            }
        profileRepository = mockk(relaxed = true)

        mockkObject(RecordingService)
        every { RecordingService.startRecording(any(), any(), any()) } returns Unit
        every { RecordingService.pauseRecording(any()) } returns Unit
        every { RecordingService.resumeRecording(any()) } returns Unit
        every { RecordingService.stopRecording(any()) } returns Unit
        every { RecordingService.cancelRecording(any()) } returns Unit
        every { RecordingService.restartRecording(any(), any(), any()) } returns Unit

        viewModel = RecordViewModel(recordingStateManager, profileRepository, SavedStateHandle())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkObject(RecordingService)
    }

    @Test
    fun `viewModel exposes stateManager flows`() {
        assertEquals(0f, viewModel.currentAmplitude.value)
        assertEquals(0, viewModel.waveformBuffer.count)
    }

    @Test
    fun `startRecording calls service`() {
        val profileId = UUID.randomUUID()
        viewModel.startRecording(context, profileId)

        verify { RecordingService.startRecording(context, RecordingOrigin.APP, profileId) }
    }

    @Test
    fun `pauseRecording calls service`() {
        viewModel.pauseRecording(context)
        verify { RecordingService.pauseRecording(context) }
    }

    @Test
    fun `resumeRecording calls service`() {
        viewModel.resumeRecording(context)
        verify { RecordingService.resumeRecording(context) }
    }

    @Test
    fun `stopRecording calls service`() {
        viewModel.stopRecording(context)
        verify { RecordingService.stopRecording(context) }
    }

    @Test
    fun `cancelRecording calls service`() {
        viewModel.cancelRecording(context)
        verify { RecordingService.cancelRecording(context) }
    }

    @Test
    fun `restartRecording calls service`() {
        val profileId = UUID.randomUUID()
        viewModel.restartRecording(context, profileId)
        verify { RecordingService.restartRecording(context, RecordingOrigin.APP, profileId) }
    }

    @Test
    fun `clearLastCompletedRecordingId calls state manager`() {
        viewModel.clearLastCompletedRecordingId()
        verify { recordingStateManager.clearLastCompletedRecordingId() }
    }

    @Test
    fun `selected profile is resolved for the recording session`() = runTest(testDispatcher) {
        val profileId = UUID.randomUUID()
        val profile = Profile(id = profileId, name = "Meeting", icon = "🎤")
        coEvery { profileRepository.getProfile(profileId) } returns profile

        val recordViewModel =
            RecordViewModel(
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                savedStateHandle = SavedStateHandle(mapOf("profileId" to profileId.toString())),
            )

        advanceUntilIdle()

        assertEquals(profileId, recordViewModel.activeProfile.value?.id)
        assertEquals("Meeting", recordViewModel.activeProfile.value?.name)
        assertEquals("🎤", recordViewModel.activeProfile.value?.icon)
        assertEquals(true, recordViewModel.isProfileHandoffResolved.value)
        assertNull(recordViewModel.entryMessage.value)

        recordViewModel.startRecording(context)

        verify { RecordingService.startRecording(context, RecordingOrigin.APP, profileId) }
    }

    @Test
    fun `missing selected profile falls back to no-profile recording`() = runTest(testDispatcher) {
        val profileId = UUID.randomUUID()
        coEvery { profileRepository.getProfile(profileId) } returns null

        val recordViewModel =
            RecordViewModel(
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                savedStateHandle = SavedStateHandle(mapOf("profileId" to profileId.toString())),
            )

        advanceUntilIdle()

        assertNull(recordViewModel.activeProfile.value)
        assertEquals(true, recordViewModel.isProfileHandoffResolved.value)
        assertEquals(
            "Profile no longer exists. Using default recording settings.",
            recordViewModel.entryMessage.value,
        )

        recordViewModel.startRecording(context)

        verify { RecordingService.startRecording(context, RecordingOrigin.APP, null) }
    }
}