package dev.chirpboard.app.feature.recording.ui

import android.database.sqlite.SQLiteException
import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.recording.service.RecordingAutoStopReason
import dev.chirpboard.app.feature.recording.service.RecordingServiceEvents
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import dev.chirpboard.app.feature.recording.session.SessionRecoveryResult
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class RecordViewModelTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var recordingManager: RecordingManager
    private lateinit var recordingStateManager: RecordingStateManager
    private lateinit var profileRepository: ProfileRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var recoveryStore: RecordingRecoveryStore
    private val serviceEvents = RecordingServiceEvents()
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
                serviceEvents = serviceEvents,
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
                serviceEvents = serviceEvents,
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
                serviceEvents = serviceEvents,
                savedStateHandle = SavedStateHandle(),
            )

        val handoffId = handoffViewModel.stopRecordingWithHandoff()
        advanceUntilIdle()

        assertEquals(recordingId, handoffId)
        coVerify { recordingManager.stopRecording() }
    }

    @Test
    fun `stopRecordingWithHandoff returns null when no active recording id`() = runTest(testDispatcher) {
        every { recordingStateManager.state } returns MutableStateFlow(RecordingState.Starting(RecordingOrigin.APP))

        val handoffId = viewModel.stopRecordingWithHandoff()

        assertNull(handoffId)
        coVerify(exactly = 0) { recordingManager.stopRecording() }
    }

    @Test
    fun `stopRecordingWithHandoff returns null for keyboard recording`() = runTest(testDispatcher) {
        every { recordingStateManager.state } returns
            MutableStateFlow(
                RecordingState.Recording(
                    origin = RecordingOrigin.KEYBOARD,
                    recordingId = UUID.randomUUID(),
                ),
            )

        val handoffId = viewModel.stopRecordingWithHandoff()
        advanceUntilIdle()

        assertNull(handoffId)
        coVerify(exactly = 0) { recordingManager.stopRecording() }
    }

    @Test
    fun `canHandoffToStudio is false while recording id is unassigned`() = runTest(testDispatcher) {
        every { recordingStateManager.state } returns MutableStateFlow(RecordingState.Starting(RecordingOrigin.APP))

        assertEquals(false, viewModel.canHandoffToStudio())
    }

    @Test
    fun `discardInterruptedSession surfaces refusal message`() = runTest(testDispatcher) {
        val sessionId = UUID.randomUUID()
        coEvery { recoveryStore.discardSession(sessionId) } returns
            SessionRecoveryResult.Failed("Recording is still being finalized. Try again in a moment.")

        viewModel.discardInterruptedSession(sessionId)
        advanceUntilIdle()

        assertEquals(
            "Recording is still being finalized. Try again in a moment.",
            viewModel.entryMessage.value,
        )
    }

    @Test
    fun `keepInterruptedSession surfaces refusal message and stays silent on success`() = runTest(testDispatcher) {
        val sessionId = UUID.randomUUID()
        coEvery { recoveryStore.keepSession(sessionId) } returns SessionRecoveryResult.Kept

        viewModel.keepInterruptedSession(sessionId)
        advanceUntilIdle()

        assertNull(viewModel.entryMessage.value)

        coEvery { recoveryStore.keepSession(sessionId) } returns
            SessionRecoveryResult.Failed("Recording is still being finalized. Try again in a moment.")

        viewModel.keepInterruptedSession(sessionId)
        advanceUntilIdle()

        assertEquals(
            "Recording is still being finalized. Try again in a moment.",
            viewModel.entryMessage.value,
        )
    }

    @Test
    fun `restartRecording while stopping is refused with in-screen message`() = runTest(testDispatcher) {
        every { recordingStateManager.state } returns
            MutableStateFlow(RecordingState.Stopping(RecordingOrigin.APP, UUID.randomUUID()))

        val stoppingViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                serviceEvents = serviceEvents,
                savedStateHandle = SavedStateHandle(),
            )

        stoppingViewModel.restartRecording()
        advanceUntilIdle()

        verify(exactly = 0) { recordingManager.restartRecording(any(), any()) }
        assertEquals(
            "Recording is already being saved. Start over isn't available right now.",
            stoppingViewModel.entryMessage.value,
        )
    }

    @Test
    fun `restartRecording while recording dispatches restart without message`() = runTest(testDispatcher) {
        every { recordingStateManager.state } returns
            MutableStateFlow(
                RecordingState.Recording(
                    origin = RecordingOrigin.APP,
                    recordingId = UUID.randomUUID(),
                ),
            )

        val recordingViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                serviceEvents = serviceEvents,
                savedStateHandle = SavedStateHandle(),
            )

        recordingViewModel.restartRecording()
        advanceUntilIdle()

        verify { recordingManager.restartRecording(RecordingOrigin.APP, null) }
        assertNull(recordingViewModel.entryMessage.value)
    }

    @Test
    fun `toggleTag surfaces message instead of crashing when the recording row is gone`() = runTest(testDispatcher) {
        val recordingId = UUID.randomUUID()
        every { recordingStateManager.state } returns
            MutableStateFlow(
                RecordingState.Recording(
                    origin = RecordingOrigin.APP,
                    recordingId = recordingId,
                ),
            )
        // FK violation: the recording row was deleted between the UI action and the insert.
        coEvery { tagRepository.addTagToRecording(recordingId, any()) } throws mockk<SQLiteException>(relaxed = true)

        val taggingViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                serviceEvents = serviceEvents,
                savedStateHandle = SavedStateHandle(),
            )
        advanceUntilIdle()

        val tagId = UUID.randomUUID()
        taggingViewModel.toggleTag(tagId)
        advanceUntilIdle()

        assertEquals(
            "Couldn't update tags. The recording may no longer exist.",
            taggingViewModel.entryMessage.value,
        )
        assertEquals(false, taggingViewModel.selectedTagIds.value.contains(tagId))
    }

    @Test
    fun `createTagForRecording surfaces message instead of crashing when the recording row is gone`() =
        runTest(testDispatcher) {
            val recordingId = UUID.randomUUID()
            every { recordingStateManager.state } returns
                MutableStateFlow(
                    RecordingState.Recording(
                        origin = RecordingOrigin.APP,
                        recordingId = recordingId,
                    ),
                )
            coEvery { tagRepository.createTag(any()) } returns Tag(name = "Idea")
            coEvery { tagRepository.addTagToRecording(recordingId, any()) } throws
                mockk<SQLiteException>(relaxed = true)

            val taggingViewModel =
                RecordViewModel(
                    recordingManager = recordingManager,
                    recordingStateManager = recordingStateManager,
                    profileRepository = profileRepository,
                    tagRepository = tagRepository,
                    recoveryStore = recoveryStore,
                    serviceEvents = serviceEvents,
                    savedStateHandle = SavedStateHandle(),
                )
            advanceUntilIdle()

            taggingViewModel.createTagForRecording("Idea")
            advanceUntilIdle()

            assertEquals(
                "Couldn't add the tag. The recording may no longer exist.",
                taggingViewModel.entryMessage.value,
            )
            assertEquals(true, taggingViewModel.selectedTagIds.value.isEmpty())
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
                serviceEvents = serviceEvents,
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

    @Test
    fun `auto start consumption latches in saved state across process death`() = runTest(testDispatcher) {
        // LIF-02: the consumed flag must live in SavedStateHandle so a restored back stack
        // (record?autoStart=true) can never re-fire an unattended microphone start.
        val savedStateHandle = SavedStateHandle()
        val firstViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                serviceEvents = serviceEvents,
                savedStateHandle = savedStateHandle,
            )

        assertEquals(false, firstViewModel.isAutoStartConsumed.value)
        firstViewModel.consumeAutoStart()
        assertEquals(true, firstViewModel.isAutoStartConsumed.value)

        // A restored ViewModel (same saved state) sees the latched flag.
        val restoredViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                serviceEvents = serviceEvents,
                savedStateHandle = savedStateHandle,
            )
        assertEquals(true, restoredViewModel.isAutoStartConsumed.value)
    }

    @Test
    fun `recoverable sessions refresh flag flips only after the store refresh completes`() = runTest(testDispatcher) {
        // LIF-02: the auto-start decision must wait for the recovery store's first refresh so
        // the empty-before-refresh window can't start the mic over a pending recovery prompt.
        val refreshGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { recoveryStore.refresh() } coAnswers { refreshGate.await() }
        val gatedViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                serviceEvents = serviceEvents,
                savedStateHandle = SavedStateHandle(),
            )

        testDispatcher.scheduler.runCurrent()
        assertEquals(false, gatedViewModel.isRecoverableSessionsRefreshed.value)

        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(true, gatedViewModel.isRecoverableSessionsRefreshed.value)
        coVerify { recoveryStore.refresh() }
    }

    @Test
    fun `recoverable sessions refresh flag flips even when refresh fails`() = runTest(testDispatcher) {
        coEvery { recoveryStore.refresh() } throws IllegalStateException("datastore corrupt")

        val failingViewModel =
            RecordViewModel(
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recoveryStore = recoveryStore,
                serviceEvents = serviceEvents,
                savedStateHandle = SavedStateHandle(),
            )

        advanceUntilIdle()

        assertEquals(true, failingViewModel.isRecoverableSessionsRefreshed.value)
    }

    // ERR-13/ERR-14: auto-stops complete through the normal save path (never
    // RecordingState.Error), so the service's event channel is the only in-app reason
    // surface — it must pass through the ViewModel and clear on acknowledgement.
    @Test
    fun `auto-stop events pass through and clear on consume`() = runTest(testDispatcher) {
        assertNull(viewModel.autoStopEvent.value)

        serviceEvents.publishAutoStop(RecordingAutoStopReason.STORAGE_CRITICAL)
        assertEquals(RecordingAutoStopReason.STORAGE_CRITICAL, viewModel.autoStopEvent.value?.reason)

        viewModel.consumeAutoStopEvent()
        assertNull(viewModel.autoStopEvent.value)
    }
}
