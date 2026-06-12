package dev.chirpboard.app.feature.recording.ui

import android.database.sqlite.SQLiteException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.feature.recording.R
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
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
    // I18N-08: snackbar/banner copy moved to resources; the mock resolves the asserted ids.
    private val appContext =
        mockk<android.content.Context>(relaxed = true) {
            every { getString(R.string.rec_msg_stop_in_progress) } returns
                "Recording is already being saved. Start over isn't available right now."
            every { getString(R.string.rec_msg_tag_update_failed) } returns
                "Couldn't update tags. The recording may no longer exist."
            every { getString(R.string.rec_msg_tag_add_failed) } returns
                "Couldn't add the tag. The recording may no longer exist."
            every { getString(R.string.rec_msg_profile_missing) } returns
                "Profile no longer exists. Using default recording settings."
        }

    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var recordingManager: RecordingManager
    private lateinit var recordingStateManager: RecordingStateManager
    private lateinit var profileRepository: ProfileRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var recordingRepository: RecordingRepository
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
        recordingRepository = mockk(relaxed = true)
        coEvery { recordingRepository.getNotes(any()) } returns null
        coEvery { recordingRepository.updateNotes(any(), any()) } returns true
        recoveryStore = mockk(relaxed = true)
        every { recoveryStore.pendingSessions } returns MutableStateFlow(emptyList())
        every { recoveryStore.actionablePendingSessions } returns MutableStateFlow(emptyList())
        coEvery { recoveryStore.refresh() } returns Unit

        viewModel =
            RecordViewModel(
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                    appContext = appContext,
                    recordingManager = recordingManager,
                    recordingStateManager = recordingStateManager,
                    profileRepository = profileRepository,
                    tagRepository = tagRepository,
                    recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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
                appContext = appContext,
                recordingManager = recordingManager,
                recordingStateManager = recordingStateManager,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                recordingRepository = recordingRepository,
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

    // AUD-02/AUD-05/ERR-14: the live-session advisory follows the service event flags with
    // notification-status priority, and clears when the service resets the session state.
    @Test
    fun `session advisory tracks service flags and clears on session reset`() = runTest(testDispatcher) {
        val collector = launch { viewModel.sessionAdvisory.collect {} }
        advanceUntilIdle()
        assertNull(viewModel.sessionAdvisory.value)

        serviceEvents.setStorageLow(true)
        advanceUntilIdle()
        assertEquals(RecordingSessionAdvisory.STORAGE_LOW, viewModel.sessionAdvisory.value)

        // Silence outranks low storage, mirroring the notification status line.
        serviceEvents.setSilenceDetected(true)
        advanceUntilIdle()
        assertEquals(RecordingSessionAdvisory.SILENCED, viewModel.sessionAdvisory.value)

        // MIC-010: a resume-time device change outranks silence but not the focus pause.
        serviceEvents.setDeviceChangedOnResume(
            dev.chirpboard.app.feature.recording.service.RecordingDeviceChange(
                fromDeviceName = "Built-in microphone",
                toDeviceName = "USB mic",
            ),
        )
        advanceUntilIdle()
        assertEquals(RecordingSessionAdvisory.DEVICE_CHANGED_ON_RESUME, viewModel.sessionAdvisory.value)

        serviceEvents.setAutoPauseReason(
            dev.chirpboard.app.feature.recording.service.RecordingAutoPauseReason.FOCUS_LOST_TRANSIENT,
        )
        advanceUntilIdle()
        assertEquals(RecordingSessionAdvisory.PAUSED_BY_FOCUS_LOSS, viewModel.sessionAdvisory.value)

        serviceEvents.resetSessionState()
        advanceUntilIdle()
        assertNull(viewModel.sessionAdvisory.value)

        collector.cancel()
    }

    // --- NOTES: live per-recording note draft (capture-time description) ---

    private fun recordingStateOf(recordingId: UUID): MutableStateFlow<RecordingState> =
        MutableStateFlow(
            RecordingState.Recording(
                origin = RecordingOrigin.APP,
                recordingId = recordingId,
            ),
        )

    private fun noteViewModel(
        stateFlow: MutableStateFlow<RecordingState>,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): RecordViewModel {
        every { recordingStateManager.state } returns stateFlow
        return RecordViewModel(
            appContext = appContext,
            recordingManager = recordingManager,
            recordingStateManager = recordingStateManager,
            profileRepository = profileRepository,
            tagRepository = tagRepository,
            recordingRepository = recordingRepository,
            recoveryStore = recoveryStore,
            serviceEvents = serviceEvents,
            savedStateHandle = savedStateHandle,
        )
    }

    @Test
    fun `note draft survives process death via saved state`() = runTest(testDispatcher) {
        val savedStateHandle = SavedStateHandle()
        val recordingId = UUID.randomUUID()
        val firstViewModel = noteViewModel(recordingStateOf(recordingId), savedStateHandle)
        advanceUntilIdle()

        firstViewModel.updateNoteDraft("Rooftop interview with Sam")
        assertEquals("Rooftop interview with Sam", firstViewModel.noteDraft.value)

        // A restored ViewModel (same saved state) sees the typed draft immediately.
        val restoredViewModel = noteViewModel(recordingStateOf(recordingId), savedStateHandle)
        assertEquals("Rooftop interview with Sam", restoredViewModel.noteDraft.value)
    }

    @Test
    fun `note draft is written through to the recording row after the debounce`() = runTest(testDispatcher) {
        val recordingId = UUID.randomUUID()
        val notesViewModel = noteViewModel(recordingStateOf(recordingId))
        advanceUntilIdle()

        notesViewModel.updateNoteDraft("Standup riff")
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { recordingRepository.updateNotes(any(), any()) }

        advanceUntilIdle()

        coVerify(exactly = 1) { recordingRepository.updateNotes(recordingId, "Standup riff") }
    }

    @Test
    fun `stopRecording persists the note draft onto the row`() = runTest(testDispatcher) {
        val recordingId = UUID.randomUUID()
        val notesViewModel = noteViewModel(recordingStateOf(recordingId))
        advanceUntilIdle()

        notesViewModel.updateNoteDraft("Q3 roadmap thoughts")
        notesViewModel.stopRecording()
        advanceUntilIdle()

        coVerify(atLeast = 1) { recordingRepository.updateNotes(recordingId, "Q3 roadmap thoughts") }
        coVerify { recordingManager.stopRecording() }
    }

    @Test
    fun `stopRecordingWithHandoff persists the note draft onto the handed-off row`() = runTest(testDispatcher) {
        val recordingId = UUID.randomUUID()
        val notesViewModel = noteViewModel(recordingStateOf(recordingId))
        advanceUntilIdle()

        notesViewModel.updateNoteDraft("Save-from-back-dialog note")
        val handoffId = notesViewModel.stopRecordingWithHandoff()
        advanceUntilIdle()

        assertEquals(recordingId, handoffId)
        coVerify(atLeast = 1) { recordingRepository.updateNotes(recordingId, "Save-from-back-dialog note") }
        coVerify { recordingManager.stopRecording() }
    }

    @Test
    fun `stop with a blank untouched draft never writes the notes column`() = runTest(testDispatcher) {
        // Guards the hydration race: a blank draft that simply has not loaded the persisted
        // note yet must never wipe what is already on the row.
        val recordingId = UUID.randomUUID()
        val notesViewModel = noteViewModel(recordingStateOf(recordingId))
        advanceUntilIdle()

        notesViewModel.stopRecording()
        advanceUntilIdle()

        coVerify(exactly = 0) { recordingRepository.updateNotes(any(), any()) }
    }

    @Test
    fun `cancelRecording discards the note draft without persisting it`() = runTest(testDispatcher) {
        val recordingId = UUID.randomUUID()
        val notesViewModel = noteViewModel(recordingStateOf(recordingId))
        advanceUntilIdle()

        notesViewModel.updateNoteDraft("Doomed note")
        notesViewModel.cancelRecording()
        advanceUntilIdle()

        assertEquals("", notesViewModel.noteDraft.value)
        coVerify(exactly = 0) { recordingRepository.updateNotes(any(), any()) }
        verify { recordingManager.cancelRecording() }
    }

    @Test
    fun `note already on the row hydrates the draft when the session is observed`() = runTest(testDispatcher) {
        // Browse Home + return rebuilds the ViewModel with an empty saved state; the note
        // written through earlier must come back from the recording row.
        val recordingId = UUID.randomUUID()
        coEvery { recordingRepository.getNotes(recordingId) } returns "Written before browsing home"

        val notesViewModel = noteViewModel(recordingStateOf(recordingId))
        advanceUntilIdle()

        assertEquals("Written before browsing home", notesViewModel.noteDraft.value)
    }

    /**
     * Destroys [viewModel] exactly the way androidx does: viewModelScope (and any pending
     * debounced flush) is cancelled BEFORE onCleared() runs. A rescue that checks the flush
     * job's liveness inside onCleared can therefore never fire — the regression these
     * clear-ordering tests guard against.
     */
    private fun clearLikeAndroidx(viewModel: RecordViewModel) {
        viewModel.viewModelScope.cancel()
        val onCleared = RecordViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(viewModel)
    }

    @Test
    fun `ViewModel cleared inside the debounce window still persists the typed note`() = runTest(testDispatcher) {
        // Browse Home while typing: the debounce keeps resetting, so nothing has been written
        // through yet when the back stack entry (and ViewModel) is destroyed.
        val recordingId = UUID.randomUUID()
        val notesViewModel = noteViewModel(recordingStateOf(recordingId))
        advanceUntilIdle()

        notesViewModel.updateNoteDraft("Typed right before Browse Home")
        testDispatcher.scheduler.runCurrent() // Debounce still pending; nothing persisted yet.
        coVerify(exactly = 0) { recordingRepository.updateNotes(any(), any()) }

        clearLikeAndroidx(notesViewModel)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            recordingRepository.updateNotes(recordingId, "Typed right before Browse Home")
        }
    }

    @Test
    fun `ViewModel cleared right after a user clear persists the blank note`() = runTest(testDispatcher) {
        // The inverse hazard: clearing the note then leaving within the debounce window must
        // not resurrect the old note on the row.
        val recordingId = UUID.randomUUID()
        val notesViewModel = noteViewModel(recordingStateOf(recordingId))
        advanceUntilIdle()

        notesViewModel.updateNoteDraft("Soon deleted")
        advanceUntilIdle() // First draft written through.
        notesViewModel.updateNoteDraft("")
        testDispatcher.scheduler.runCurrent() // Clear still inside the debounce window.

        clearLikeAndroidx(notesViewModel)
        advanceUntilIdle()

        coVerify(exactly = 1) { recordingRepository.updateNotes(recordingId, "") }
    }

    @Test
    fun `ViewModel cleared with no unconfirmed edit never rewrites the row`() = runTest(testDispatcher) {
        // A hydrated-but-untouched draft has nothing to rescue; clearing must not write.
        val recordingId = UUID.randomUUID()
        coEvery { recordingRepository.getNotes(recordingId) } returns "Already on the row"
        val notesViewModel = noteViewModel(recordingStateOf(recordingId))
        advanceUntilIdle()

        clearLikeAndroidx(notesViewModel)
        advanceUntilIdle()

        coVerify(exactly = 0) { recordingRepository.updateNotes(any(), any()) }
    }

    @Test
    fun `session ending through any stop path flushes the draft and resets it`() = runTest(testDispatcher) {
        // Auto-stops (storage critical, focus loss…) never call stopRecording() on this
        // ViewModel; the Idle transition is their only flush point.
        val recordingId = UUID.randomUUID()
        val stateFlow = recordingStateOf(recordingId)
        val notesViewModel = noteViewModel(stateFlow)
        advanceUntilIdle()

        notesViewModel.updateNoteDraft("Captured before auto-stop")
        stateFlow.value = RecordingState.Idle
        advanceUntilIdle()

        coVerify(atLeast = 1) { recordingRepository.updateNotes(recordingId, "Captured before auto-stop") }
        assertEquals("", notesViewModel.noteDraft.value)
    }
}
