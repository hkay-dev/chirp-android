package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.toUserMessage
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingLibraryStats
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.TranscriptPreview
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import dev.chirpboard.app.data.repository.TagRepository
import androidx.lifecycle.viewModelScope
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.recording.importing.AudioImportOrchestrator
import dev.chirpboard.app.feature.recording.importing.AudioImportResult
import dev.chirpboard.app.feature.recording.service.RecordingAutoStopReason
import dev.chirpboard.app.feature.recording.service.RecordingServiceEvents
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import dev.chirpboard.app.feature.recording.session.SessionRecoveryResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    // I18N-08: snackbar copy moved to resources; the mock resolves the ids these tests assert.
    private val appContext =
        mockk<android.content.Context>(relaxed = true) {
            every { getString(dev.chirpboard.app.core.ui.R.string.rec_msg_requeued_transcription) } returns
                "Re-queued for transcription"
            every { getString(dev.chirpboard.app.core.ui.R.string.rec_msg_transcription_cancelled) } returns
                "Transcription cancelled"
            every { getString(dev.chirpboard.app.feature.recording.R.string.rec_msg_queued_for_transcription) } returns
                "Queued for transcription"
            every { getString(dev.chirpboard.app.core.ui.R.string.rec_msg_delete_failed) } returns
                "Couldn't delete the recording"
        }
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var recordingManager: RecordingManager
    private lateinit var tagRepository: TagRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var transcriptionQueueManager: TranscriptionRecovery
    private lateinit var recordingTextEnrichment: RecordingTextEnhancementPort
    private lateinit var audioImportOrchestrator: AudioImportOrchestrator
    private lateinit var sessionRecovery: RecordingRecoveryStore
    private lateinit var savedStateHandle: SavedStateHandle
    private val serviceEvents = RecordingServiceEvents()

    private lateinit var viewModel: HomeViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        recordingRepository =
            mockk(relaxed = true) {
                every { getAllRecordings() } returns emptyFlow()
                every { getTranscriptPreviewsFlow(any(), any()) } returns flowOf(RepositoryFlowState(emptyMap()))
            }
        recordingManager =
            mockk(relaxed = true) {
                every { state } returns MutableStateFlow(RecordingState.Idle)
            }
        tagRepository =
            mockk(relaxed = true) {
                every { getAllTags() } returns emptyFlow()
                every { getTagsForRecordingIdsFlow(any()) } returns flowOf(RepositoryFlowState(emptyMap()))
            }
        profileRepository =
            mockk(relaxed = true) {
                every { getAllProfiles() } returns emptyFlow()
            }
        transcriptionQueueManager = mockk(relaxed = true)
        recordingTextEnrichment = mockk(relaxed = true)
        audioImportOrchestrator = mockk(relaxed = true)
        sessionRecovery = mockk(relaxed = true)
        every { sessionRecovery.pendingSessions } returns MutableStateFlow(emptyList())
        every { sessionRecovery.actionablePendingSessions } returns MutableStateFlow(emptyList())
        coEvery { sessionRecovery.refresh() } returns Unit
        val playbackController =
            mockk<dev.chirpboard.app.core.playback.RecordingPlaybackController>(relaxed = true) {
                every { state } returns MutableStateFlow(dev.chirpboard.app.core.playback.RecordingPlaybackState())
            }
        savedStateHandle = SavedStateHandle()

        viewModel =
            HomeViewModel(
                appContext,
                recordingRepository,
                recordingManager,
                tagRepository,
                profileRepository,
                transcriptionQueueManager,
                recordingTextEnrichment,
                audioImportOrchestrator,
                sessionRecovery,
                playbackController,
                savedStateHandle,
                // Share the test scheduler so flowOn transforms stay on the single dispatcher
                // that advanceUntilIdle drives — keeps the existing tests deterministic.
                testDispatcher,
                serviceEvents,
            )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onSearchQueryChange updates query state`() =
        runTest {
            viewModel.onSearchQueryChange("test search")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("test search", viewModel.searchQuery.value)
        }

    @Test
    fun `home observes the recordings table once`() {
        verify(exactly = 1) { recordingRepository.getAllRecordings() }
    }

    @Test
    fun `deriveHomeQuickStarts ranks pinned first and caps to four`() {
        val profileA = Profile(id = UUID.randomUUID(), name = "Alpha", sortOrder = 2, isQuickStartPinned = true)
        val profileB = Profile(id = UUID.randomUUID(), name = "Beta", sortOrder = 1, isQuickStartPinned = true)
        val profileC = Profile(id = UUID.randomUUID(), name = "Gamma", sortOrder = 3)
        val profileD = Profile(id = UUID.randomUUID(), name = "Delta", sortOrder = 4)
        val profileE = Profile(id = UUID.randomUUID(), name = "Epsilon", sortOrder = 5)

        val quickStarts =
            deriveHomeQuickStarts(
                profiles = listOf(profileA, profileB, profileC, profileD, profileE),
                recordings = listOf(
                    Recording(
                        id = UUID.randomUUID(),
                        title = "Newest",
                        audioPath = "c.m4a",
                        source = RecordingSource.APP,
                        profileId = profileC.id,
                        createdAt = Date(5_000L),
                    ),
                    Recording(
                        id = UUID.randomUUID(),
                        title = "Next",
                        audioPath = "d.m4a",
                        source = RecordingSource.APP,
                        profileId = profileD.id,
                        createdAt = Date(4_000L),
                    ),
                    Recording(
                        id = UUID.randomUUID(),
                        title = "Oldest kept",
                        audioPath = "e.m4a",
                        source = RecordingSource.APP,
                        profileId = profileE.id,
                        createdAt = Date(3_000L),
                    ),
                ),
            )

        assertEquals(listOf(profileB.id, profileA.id, profileC.id, profileD.id), quickStarts.map(HomeQuickStartEntry::id))
        assertEquals(listOf(true, true, false, false), quickStarts.map(HomeQuickStartEntry::isPinned))
    }

    @Test
    fun `deriveHomeQuickStarts excludes null missing and duplicate profiles`() {
        val pinned = Profile(id = UUID.randomUUID(), name = "Pinned", sortOrder = 1, isQuickStartPinned = true)
        val recent = Profile(id = UUID.randomUUID(), name = "Recent", sortOrder = 2)
        val deletedProfileId = UUID.randomUUID()

        val quickStarts =
            deriveHomeQuickStarts(
                profiles = listOf(pinned, recent),
                recordings = listOf(
                    Recording(
                        id = UUID.randomUUID(),
                        title = "Pinned recent",
                        audioPath = "p.m4a",
                        source = RecordingSource.APP,
                        profileId = pinned.id,
                        createdAt = Date(5_000L),
                    ),
                    Recording(
                        id = UUID.randomUUID(),
                        title = "Recent 1",
                        audioPath = "r1.m4a",
                        source = RecordingSource.APP,
                        profileId = recent.id,
                        createdAt = Date(4_000L),
                    ),
                    Recording(
                        id = UUID.randomUUID(),
                        title = "Recent 2",
                        audioPath = "r2.m4a",
                        source = RecordingSource.APP,
                        profileId = recent.id,
                        createdAt = Date(3_000L),
                    ),
                    Recording(
                        id = UUID.randomUUID(),
                        title = "Deleted",
                        audioPath = "gone.m4a",
                        source = RecordingSource.APP,
                        profileId = deletedProfileId,
                        createdAt = Date(2_000L),
                    ),
                    Recording(
                        id = UUID.randomUUID(),
                        title = "No profile",
                        audioPath = "none.m4a",
                        source = RecordingSource.APP,
                        profileId = null,
                        createdAt = Date(1_000L),
                    ),
                ),
            )

        assertEquals(listOf(pinned.id, recent.id), quickStarts.map(HomeQuickStartEntry::id))
    }

    @Test
    fun `shouldShowStuckRecoveryAction includes pending enhancement`() {
        assertTrue(shouldShowStuckRecoveryAction(RecordingStatus.PENDING_ENHANCEMENT))
    }

    @Test
    fun `isAppBarCollapsed is false below and at the threshold`() {
        assertFalse(isAppBarCollapsed(0f))
        assertFalse(isAppBarCollapsed(0.5f))
    }

    @Test
    fun `isAppBarCollapsed is true past the threshold`() {
        assertTrue(isAppBarCollapsed(0.51f))
        assertTrue(isAppBarCollapsed(1f))
    }

    @Test
    fun `nextFabExpandedState collapses only past the upper threshold`() {
        // Scrolled well past the first row -> collapse regardless of prior state.
        assertFalse(
            nextFabExpandedState(
                previousExpanded = true,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 65,
            ),
        )
        // Off the first row entirely -> always collapsed.
        assertFalse(
            nextFabExpandedState(
                previousExpanded = true,
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0,
            ),
        )
    }

    @Test
    fun `nextFabExpandedState re-expands only at or below the lower threshold`() {
        assertTrue(
            nextFabExpandedState(
                previousExpanded = false,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 32,
            ),
        )
        assertTrue(
            nextFabExpandedState(
                previousExpanded = false,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
            ),
        )
    }

    @Test
    fun `nextFabExpandedState holds prior decision inside the dead band`() {
        // In the 32-64px band the previous decision is retained (no flicker either way).
        assertTrue(
            nextFabExpandedState(
                previousExpanded = true,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 48,
            ),
        )
        assertFalse(
            nextFabExpandedState(
                previousExpanded = false,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 48,
            ),
        )
    }

    @Test
    fun `homeContentPhase holds LOADING until first load resolves`() {
        // LOAD-3: before the first emission, never claim EMPTY even with a zero count + no filter.
        assertEquals(
            HomeContentPhase.LOADING,
            homeContentPhase(
                contentLoaded = false,
                libraryEmpty = true,
                searchBlank = true,
                filterAll = true,
            ),
        )
    }

    @Test
    fun `homeContentPhase is EMPTY only once loaded and genuinely empty`() {
        assertEquals(
            HomeContentPhase.EMPTY,
            homeContentPhase(
                contentLoaded = true,
                libraryEmpty = true,
                searchBlank = true,
                filterAll = true,
            ),
        )
    }

    @Test
    fun `homeContentPhase is LIST once loaded with recordings`() {
        assertEquals(
            HomeContentPhase.LIST,
            homeContentPhase(
                contentLoaded = true,
                libraryEmpty = false,
                searchBlank = true,
                filterAll = true,
            ),
        )
    }

    @Test
    fun `homeContentPhase is LIST when a search or filter is active even if empty`() {
        // An active search/filter with no matches is a filter-empty case handled inside the list,
        // not the first-run empty illustration.
        assertEquals(
            HomeContentPhase.LIST,
            homeContentPhase(
                contentLoaded = true,
                libraryEmpty = true,
                searchBlank = false,
                filterAll = true,
            ),
        )
        assertEquals(
            HomeContentPhase.LIST,
            homeContentPhase(
                contentLoaded = true,
                libraryEmpty = true,
                searchBlank = true,
                filterAll = false,
            ),
        )
    }

    @Test
    fun `playback row state ignores progress ticks`() {
        val recordingId = UUID.randomUUID()
        val first =
            dev.chirpboard.app.core.playback.RecordingPlaybackState(
                recordingId = recordingId,
                title = "Meeting notes",
                audioPath = "/tmp/meeting.m4a",
                positionMs = 1_000L,
                durationMs = 10_000L,
                isPlaying = true,
            )
        val tick =
            first.copy(
                positionMs = 2_000L,
                durationMs = 12_000L,
            )

        assertEquals(first.toHomeRowState(), tick.toHomeRowState())
    }

    @Test
    fun `retryTranscription queues recording for transcription`() =
        runTest {
            val recordingId = UUID.randomUUID()
            coEvery { transcriptionQueueManager.retry(recordingId) } returns ManualRecoveryResult.ENQUEUED

            viewModel.retryTranscription(failedDisplayItem(recordingId))
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transcriptionQueueManager.retry(recordingId) }
            assertEquals("Re-queued for transcription", viewModel.errorMessage.value)
        }

    @Test
    fun `retryTranscription surfaces refused outcome instead of false success`() =
        runTest {
            val recordingId = UUID.randomUUID()
            coEvery { transcriptionQueueManager.retry(recordingId) } returns
                ManualRecoveryResult.BLOCKED_ACTIVE_WORK

            viewModel.retryTranscription(failedDisplayItem(recordingId))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                ManualRecoveryResult.BLOCKED_ACTIVE_WORK.toUserMessage(appContext, "Re-queued for transcription"),
                viewModel.errorMessage.value,
            )
        }

    @Test
    fun `cancelTranscription cancels processing and posts a status notice`() =
        runTest {
            val recordingId = UUID.randomUUID()
            coEvery { transcriptionQueueManager.cancelProcessing(recordingId) } returns Unit

            viewModel.cancelTranscription(failedDisplayItem(recordingId))
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transcriptionQueueManager.cancelProcessing(recordingId) }
            assertEquals("Transcription cancelled", viewModel.statusMessage.value)
            assertNull(viewModel.errorMessage.value)
        }

    @Test
    fun `startManualTranscription queues a deliberately skipped recording`() =
        runTest {
            val recordingId = UUID.randomUUID()
            coEvery { transcriptionQueueManager.retranscribe(recordingId) } returns ManualRecoveryResult.ENQUEUED

            viewModel.startManualTranscription(failedDisplayItem(recordingId))
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transcriptionQueueManager.retranscribe(recordingId) }
            assertEquals("Queued for transcription", viewModel.statusMessage.value)
        }

    @Test
    fun `stats derive from the full-table library aggregate not the capped list`() =
        runTest {
            // DAT-006: 750 recordings in the table while the capped list flow carries none.
            every { recordingRepository.getLibraryStats() } returns
                flowOf(
                    RepositoryFlowState(
                        RecordingLibraryStats(
                            totalCount = 750,
                            totalDurationMs = 12_345L,
                            completedCount = 700,
                        ),
                    ),
                )
            val statsViewModel = createHomeViewModel()

            val collector = launch { statsViewModel.stats.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(750, statsViewModel.stats.value.totalRecordings)
            assertEquals(12_345L, statsViewModel.stats.value.totalDurationMs)
            assertEquals(700, statsViewModel.stats.value.completedCount)
            collector.cancel()
        }

    @Test
    fun `isHomeListCapped flips only past the home list row cap`() =
        runTest {
            val statsFlow =
                MutableStateFlow(
                    RepositoryFlowState(RecordingLibraryStats(totalCount = 500)),
                )
            every { recordingRepository.getLibraryStats() } returns statsFlow
            val cappedViewModel = createHomeViewModel()

            val collector = launch { cappedViewModel.isHomeListCapped.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(false, cappedViewModel.isHomeListCapped.value)

            statsFlow.value = RepositoryFlowState(RecordingLibraryStats(totalCount = 501))
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(true, cappedViewModel.isHomeListCapped.value)
            collector.cancel()
        }

    @Test
    fun `enrichmentFailureHintRes classifies network failures`() {
        assertEquals(
            dev.chirpboard.app.feature.recording.R.string.rec_msg_enrichment_hint_network,
            enrichmentFailureHintRes(java.io.IOException("Unable to resolve host")),
        )
        assertEquals(
            dev.chirpboard.app.feature.recording.R.string.rec_msg_enrichment_hint_generic,
            enrichmentFailureHintRes(IllegalStateException("HTTP 500")),
        )
    }

    @Test
    fun `homeProcessingNoteRes maps machine codes to friendly copy and hides raw text`() {
        // I18N-05/I18N-06: typed kinds map to resources; raw/legacy text falls back to the
        // generic stuck-state line (null), never the persisted message itself.
        assertEquals(
            dev.chirpboard.app.feature.recording.R.string.rec_note_queue_handoff,
            homeProcessingNoteRes("recoverable_queue_handoff:Transcription was interrupted|attemptAt=123"),
        )
        assertEquals(
            dev.chirpboard.app.feature.recording.R.string.rec_note_stale_recovered,
            homeProcessingNoteRes("recoverable_stale_transcribing:Recovered stale transcribing state"),
        )
        assertEquals(
            dev.chirpboard.app.feature.recording.R.string.rec_note_manual_recovery,
            homeProcessingNoteRes("manual_recovery:user_retry|attemptAt=9"),
        )
        assertNull(homeProcessingNoteRes("java.io.IOException: ENOSPC raw text"))
        assertNull(homeProcessingNoteRes(null))
    }

    private fun failedDisplayItem(recordingId: UUID): RecordingDisplayItem {
        val recording =
            mockk<Recording>(relaxed = true) {
                every { id } returns recordingId
                every { status } returns RecordingStatus.FAILED
            }
        return mockk<RecordingDisplayItem>(relaxed = true) {
            every { this@mockk.id } returns recordingId
            every { this@mockk.status } returns RecordingStatus.FAILED
            every { this@mockk.recording } returns recording
        }
    }

    @Test
    fun `recoverStuckItem resets status to pending and enqueues`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val recording =
                mockk<Recording>(relaxed = true) {
                    every { id } returns recordingId
                    every { status } returns RecordingStatus.PENDING_TRANSCRIPTION
                }

            val displayItem =
                mockk<dev.chirpboard.app.feature.recording.ui.RecordingDisplayItem>(relaxed = true) {
                    every { this@mockk.id } returns recordingId
                    every { this@mockk.status } returns RecordingStatus.PENDING_TRANSCRIPTION
                    every { this@mockk.recording } returns recording
                }

            viewModel.recoverStuckItem(displayItem)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transcriptionQueueManager.recoverPendingTranscription(recordingId) }
        }

    @Test
    fun `recoverStuckItem recovers pending enhancement`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val recording =
                mockk<Recording>(relaxed = true) {
                    every { id } returns recordingId
                    every { status } returns RecordingStatus.PENDING_ENHANCEMENT
                }

            val displayItem =
                mockk<dev.chirpboard.app.feature.recording.ui.RecordingDisplayItem>(relaxed = true) {
                    every { this@mockk.id } returns recordingId
                    every { this@mockk.status } returns RecordingStatus.PENDING_ENHANCEMENT
                    every { this@mockk.recording } returns recording
                }

            viewModel.recoverStuckItem(displayItem)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transcriptionQueueManager.recoverPendingEnhancement(recordingId) }
        }

    @Test
    fun `importAudio reuses orchestrator and surfaces recovery message`() =
        runTest {
            val uri = mockk<android.net.Uri>()
            val recordingId = UUID.randomUUID()

            coEvery { audioImportOrchestrator.import(uri) } returns
                AudioImportResult.SavedPendingRecovery(
                    recordingId = recordingId,
                    message = "Import finished, but queue handoff failed. Recovery is ready on startup.",
                )

            viewModel.importAudio(uri)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { audioImportOrchestrator.import(uri) }
            assertEquals(
                "Import finished, but queue handoff failed. Recovery is ready on startup.",
                viewModel.errorMessage.value,
            )
            assertEquals(recordingId, viewModel.openStudioForRecordingId.value)
        }

    @Test
    fun `search includes live capture and finalizing RECORDING title matches`() =
        runTest(testDispatcher) {
            // TST-004: virtual time past the search debounce replaces the former
            // runBlocking + Dispatchers.Unconfined + delay(300) real-time wait.
            val liveRecordingId = UUID.randomUUID()
            val finalizingRecordingId = UUID.randomUUID()
            val recordingStateFlow =
                MutableStateFlow<RecordingState>(
                    RecordingState.Recording(
                        origin = dev.chirpboard.app.core.recording.RecordingOrigin.APP,
                        recordingId = liveRecordingId,
                    ),
                )
            val localRecordingManager =
                mockk<RecordingManager>(relaxed = true) {
                    every { state } returns recordingStateFlow
                }

            val completed =
                Recording(
                    id = UUID.randomUUID(),
                    title = "Team sync",
                    audioPath = "/tmp/done.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.COMPLETED,
                )
            val liveCapture =
                Recording(
                    id = liveRecordingId,
                    title = "Team sync live",
                    audioPath = "/tmp/live.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.RECORDING,
                )
            val finalizing =
                Recording(
                    id = finalizingRecordingId,
                    title = "Team sync finalizing",
                    audioPath = "/tmp/final.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.RECORDING,
                )

            every { recordingRepository.getAllRecordings() } returns
                flowOf(RepositoryFlowState(listOf(completed, liveCapture, finalizing)))
            every { recordingRepository.searchRecordings("team") } returns
                flowOf(
                    RepositoryFlowState(listOf(completed)),
                )

            val localViewModel = createHomeViewModel(recordingManagerOverride = localRecordingManager)
            val collector = launch { localViewModel.displayItems.collect { } }
            runCurrent()

            localViewModel.onSearchQueryChange("team")
            advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                setOf(completed.id, liveRecordingId, finalizingRecordingId),
                localViewModel.displayItems.value.map { it.id }.toSet(),
            )
            collector.cancel()
        }

    @Test
    fun `displayItems refreshes metadata-only enrichment while search remains active`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val recording =
                Recording(
                    id = recordingId,
                    title = "Team sync",
                    audioPath = "/tmp/team.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.COMPLETED,
                    createdAt = Date(5_000L),
                )
            val tag = Tag(id = UUID.randomUUID(), name = "Important", color = "#ff0000")
            val previewFlow =
                MutableStateFlow(
                    RepositoryFlowState(
                        mapOf(recordingId to samplePreview(recordingId, previewText = "old text", summary = "old summary")),
                    ),
                )
            val tagFlow =
                MutableStateFlow<RepositoryFlowState<Map<UUID, List<Tag>>>>(
                    RepositoryFlowState(emptyMap()),
                )

            every { recordingRepository.getAllRecordings() } returns flowOf(RepositoryFlowState(listOf(recording)))
            every { recordingRepository.searchRecordings("team") } returns flowOf(RepositoryFlowState(listOf(recording)))
            every { recordingRepository.getTranscriptPreviewsFlow(listOf(recordingId), any()) } returns previewFlow
            every { tagRepository.getTagsForRecordingIdsFlow(listOf(recordingId)) } returns tagFlow

            val localViewModel =
                createHomeViewModel(
                    savedStateHandle = SavedStateHandle(mapOf("searchQuery" to "team")),
                )
            val collector = launch { localViewModel.displayItems.collect { } }
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("old summary", localViewModel.displayItems.value.single().summary)

            previewFlow.value =
                RepositoryFlowState(
                    mapOf(recordingId to samplePreview(recordingId, previewText = "new text", summary = "new summary")),
                )
            tagFlow.value = RepositoryFlowState(mapOf(recordingId to listOf(tag)))
            testDispatcher.scheduler.advanceUntilIdle()

            val refreshed = localViewModel.displayItems.value.single()
            assertEquals(recordingId, refreshed.id)
            assertEquals("new summary", refreshed.summary)
            assertEquals(listOf("Important"), refreshed.tags.map(Tag::name))
            collector.cancel()
        }

    @Test
    fun `displayItems includes background finalize recording while idle`() =
        runTest(testDispatcher) {
            // TST-004: the blank default query bypasses the debounce, so plain virtual-time
            // advancement replaces the former runBlocking + delay(300) real-time wait.
            val recordingId = UUID.randomUUID()
            val finalizingRecording =
                Recording(
                    id = recordingId,
                    title = "Morning notes",
                    audioPath = "/tmp/morning.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.RECORDING,
                )
            every { recordingRepository.getAllRecordings() } answers {
                flowOf(RepositoryFlowState(listOf(finalizingRecording)))
            }
            val idleStateFlow = MutableStateFlow(RecordingState.Idle)
            every { recordingManager.state } returns idleStateFlow

            val localViewModel = createHomeViewModel()

            val collector = launch { localViewModel.displayItems.collect { } }
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(recordingId), localViewModel.displayItems.value.map { it.id })
            assertFalse(localViewModel.displayItems.value.single().isLiveCapture)
            collector.cancel()
        }

    @Test
    fun `displayItems includes finalizing recording while stop is in progress`() =
        runTest(testDispatcher) {
            // TST-004: virtual time replaces the former runBlocking + delay(300) wait.
            val recordingId = UUID.randomUUID()
            val finalizingRecording =
                Recording(
                    id = recordingId,
                    title = "Morning notes",
                    audioPath = "/tmp/morning.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.RECORDING,
                )
            every { recordingRepository.getAllRecordings() } answers {
                flowOf(RepositoryFlowState(listOf(finalizingRecording)))
            }
            val stoppingStateFlow =
                MutableStateFlow(
                    RecordingState.Stopping(
                        origin = dev.chirpboard.app.core.recording.RecordingOrigin.APP,
                        recordingId = recordingId,
                    ),
                )
            every { recordingManager.state } returns stoppingStateFlow

            val localViewModel = createHomeViewModel()

            val collector = launch { localViewModel.displayItems.collect { } }
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(recordingId), localViewModel.displayItems.value.map { it.id })
            collector.cancel()
        }

    @Test
    fun `live capture marker follows the active recording state`() {
        val recordingId = UUID.randomUUID()
        val recording =
            Recording(
                id = recordingId,
                title = "Live",
                audioPath = "/tmp/live.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.RECORDING,
            )

        assertTrue(
            isLiveCaptureHomeListItem(
                recording,
                RecordingState.Recording(
                    origin = dev.chirpboard.app.core.recording.RecordingOrigin.APP,
                    recordingId = recordingId,
                ),
            ),
        )
        assertTrue(
            isLiveCaptureHomeListItem(
                recording,
                RecordingState.Paused(
                    origin = dev.chirpboard.app.core.recording.RecordingOrigin.APP,
                    recordingId = recordingId,
                ),
            ),
        )
        assertFalse(
            isLiveCaptureHomeListItem(
                recording,
                RecordingState.Stopping(
                    origin = dev.chirpboard.app.core.recording.RecordingOrigin.APP,
                    recordingId = recordingId,
                ),
            ),
        )
        assertTrue(isHomeListProcessingItem(recording))
    }

    @Test
    fun `discardInterruptedSession surfaces refusal message`() =
        runTest {
            val sessionId = UUID.randomUUID()
            coEvery { sessionRecovery.discardSession(sessionId) } returns
                SessionRecoveryResult.Failed("Recording is still being finalized. Try again in a moment.")

            viewModel.discardInterruptedSession(sessionId)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "Recording is still being finalized. Try again in a moment.",
                viewModel.errorMessage.value,
            )
            coVerify { sessionRecovery.refresh() }
        }

    @Test
    fun `keepInterruptedSession surfaces refusal message`() =
        runTest {
            val sessionId = UUID.randomUUID()
            coEvery { sessionRecovery.keepSession(sessionId) } returns
                SessionRecoveryResult.Failed("Recording is still being finalized. Try again in a moment.")

            viewModel.keepInterruptedSession(sessionId)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "Recording is still being finalized. Try again in a moment.",
                viewModel.errorMessage.value,
            )
        }

    @Test
    fun `discardInterruptedSession stays silent on success`() =
        runTest {
            val sessionId = UUID.randomUUID()
            coEvery { sessionRecovery.discardSession(sessionId) } returns SessionRecoveryResult.Discarded

            viewModel.discardInterruptedSession(sessionId)
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.errorMessage.value)
        }

    @Test
    fun `importAudio navigates to studio after successful import`() =
        runTest {
            val uri = mockk<android.net.Uri>()
            val recordingId = UUID.randomUUID()

            coEvery { audioImportOrchestrator.import(uri) } returns
                AudioImportResult.SavedAndQueued(recordingId)

            viewModel.importAudio(uri)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(recordingId, viewModel.openStudioForRecordingId.value)
        }

    @Test
    fun `libraryLoadState latches loaded and reports emptiness from the same emission`() =
        runTest {
            // LOAD-3: the gate stays false until Room emits, so Home holds the skeleton rather than
            // flashing the empty state, then latches true on the first emission (here, an empty list
            // — a genuinely-empty load is still a "loaded" signal).
            every { recordingRepository.getAllRecordings() } returns
                flowOf(RepositoryFlowState(emptyList()))

            val localViewModel = createHomeViewModel()
            assertFalse(localViewModel.libraryLoadState.value.loaded)

            val collector = launch { localViewModel.libraryLoadState.collect { } }
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(localViewModel.libraryLoadState.value.loaded)
            assertTrue(localViewModel.libraryLoadState.value.empty)
            collector.cancel()
        }

    // --- TST-003: delete journey (no undo exists; the ordering contract is the safety net) ---

    @Test
    fun `deleteRecording cancels work and deletes the db row before the audio file`() =
        runTest(testDispatcher) {
            val audioFile = File.createTempFile("home-delete", ".m4a").apply { writeText("audio-bytes") }
            val item = displayItemFor(audioFile.absolutePath)

            runDeleteToCompletion(item)

            // Ordering contract: queued/running work is cancelled, then the DB row goes
            // first (cascade deletes the transcript); the file is only best-effort cleanup.
            coVerifyOrder {
                transcriptionQueueManager.cancelProcessing(item.id)
                recordingRepository.deleteById(item.id)
            }
            assertFalse(audioFile.exists())
            assertNull(viewModel.errorMessage.value)
        }

    @Test
    fun `deleteRecording db failure keeps the audio file and surfaces the error`() =
        runTest(testDispatcher) {
            val audioFile = File.createTempFile("home-delete", ".m4a").apply { writeText("audio-bytes") }
            val item = displayItemFor(audioFile.absolutePath)
            coEvery { recordingRepository.deleteById(item.id) } throws RuntimeException("db down")

            viewModel.deleteRecording(item)
            testDispatcher.scheduler.advanceUntilIdle()

            // Data-loss guard: if the row could not be deleted, the audio must survive.
            assertTrue(audioFile.exists())
            assertEquals("Couldn't delete the recording", viewModel.errorMessage.value)
            audioFile.delete()
        }

    @Test
    fun `deleteRecording file-delete failure is non-fatal`() =
        runTest(testDispatcher) {
            // A directory with content makes File.delete() return false — the undeletable-file case.
            val undeletable = createTempDir(prefix = "home-delete-dir")
            File(undeletable, "child.bin").writeText("x")
            val item = displayItemFor(undeletable.absolutePath)

            runDeleteToCompletion(item)

            coVerify { recordingRepository.deleteById(item.id) }
            assertNull(viewModel.errorMessage.value)
            undeletable.deleteRecursively()
        }

    /**
     * Runs deleteRecording and deterministically awaits its launched coroutine, which hops to
     * Dispatchers.IO for the file deletion. runTest shares [testDispatcher]'s scheduler, so
     * while [Job.join] suspends the test body, runTest keeps draining the scheduler — including
     * the continuation the IO dispatcher posts back. Bounded by runTest's own timeout; no
     * wall-clock sleeps or polling.
     */
    private suspend fun runDeleteToCompletion(item: RecordingDisplayItem) {
        val before = viewModel.viewModelScope.coroutineContext.job.children.toSet()
        viewModel.deleteRecording(item)
        val deleteJob: Job =
            viewModel.viewModelScope.coroutineContext.job.children.first { it !in before }
        deleteJob.join()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun displayItemFor(audioPath: String): RecordingDisplayItem =
        RecordingDisplayItem(
            recording =
                Recording(
                    id = UUID.randomUUID(),
                    title = "To delete",
                    audioPath = audioPath,
                    source = RecordingSource.APP,
                    status = RecordingStatus.COMPLETED,
                ),
        )

    @Test
    fun `raw cloud capture cannot start playback or audio sharing`() =
        runTest(testDispatcher) {
            val playbackController =
                mockk<dev.chirpboard.app.core.playback.RecordingPlaybackController>(relaxed = true) {
                    every { state } returns MutableStateFlow(dev.chirpboard.app.core.playback.RecordingPlaybackState())
                }
            val localViewModel = createHomeViewModel(playbackControllerOverride = playbackController)
            val rawItem =
                RecordingDisplayItem(
                    recording =
                        Recording(
                            id = UUID.randomUUID(),
                            title = "Cloud dictation",
                            audioPath = "/tmp/cloud-dictation.f32pcm",
                            source = RecordingSource.KEYBOARD,
                            status = RecordingStatus.PENDING_TRANSCRIPTION,
                        ),
                )
            val shareContext = mockk<Context>(relaxed = true)

            localViewModel.playRecording(rawItem)
            localViewModel.shareRecording(rawItem, shareContext)
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(rawItem.isAudioReady)
            assertTrue(isPlaybackAndShareReadyAudioPath("/tmp/cloud-dictation.wav"))
            verify(exactly = 0) { playbackController.play(any(), any(), any()) }
            verify(exactly = 0) { shareContext.startActivity(any()) }
        }

    @Test
    fun `deleteRecording stops the mini player when deleting the playing recording`() =
        runTest(testDispatcher) {
            // Matches ProcessingStudioViewModel.deleteRecording: the mini player must never
            // keep playing (and holding) audio for a row that no longer exists.
            val audioFile = File.createTempFile("home-delete", ".m4a").apply { writeText("audio") }
            val item = displayItemFor(audioFile.absolutePath)
            val playbackController =
                mockk<dev.chirpboard.app.core.playback.RecordingPlaybackController>(relaxed = true) {
                    every { state } returns
                        MutableStateFlow(
                            dev.chirpboard.app.core.playback.RecordingPlaybackState(
                                recordingId = item.id,
                                title = item.title,
                                audioPath = item.audioPath,
                                isPlaying = true,
                            ),
                        )
                }
            viewModel = createHomeViewModel(playbackControllerOverride = playbackController)

            runDeleteToCompletion(item)

            coVerify { recordingRepository.deleteById(item.id) }
            io.mockk.verify(exactly = 1) { playbackController.stop() }
        }

    @Test
    fun `deleteRecording leaves the mini player alone when a different recording is playing`() =
        runTest(testDispatcher) {
            val audioFile = File.createTempFile("home-delete", ".m4a").apply { writeText("audio") }
            val item = displayItemFor(audioFile.absolutePath)
            val playbackController =
                mockk<dev.chirpboard.app.core.playback.RecordingPlaybackController>(relaxed = true) {
                    every { state } returns
                        MutableStateFlow(
                            dev.chirpboard.app.core.playback.RecordingPlaybackState(
                                recordingId = UUID.randomUUID(),
                                title = "Some other recording",
                                audioPath = "/tmp/other.m4a",
                                isPlaying = true,
                            ),
                        )
                }
            viewModel = createHomeViewModel(playbackControllerOverride = playbackController)

            runDeleteToCompletion(item)

            coVerify { recordingRepository.deleteById(item.id) }
            io.mockk.verify(exactly = 0) { playbackController.stop() }
        }

    // ERR-13/ERR-14: Home surfaces the same service auto-stop channel as the record screen.
    @Test
    fun `auto-stop events pass through and clear on consume`() =
        runTest(testDispatcher) {
            assertNull(viewModel.autoStopEvent.value)

            serviceEvents.publishAutoStop(RecordingAutoStopReason.STORAGE_CRITICAL)
            assertEquals(RecordingAutoStopReason.STORAGE_CRITICAL, viewModel.autoStopEvent.value?.reason)

            viewModel.consumeAutoStopEvent()
            assertNull(viewModel.autoStopEvent.value)
        }

    private fun createHomeViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        recordingManagerOverride: RecordingManager = recordingManager,
        playbackControllerOverride: dev.chirpboard.app.core.playback.RecordingPlaybackController =
            mockk(relaxed = true) {
                every { state } returns MutableStateFlow(dev.chirpboard.app.core.playback.RecordingPlaybackState())
            },
    ): HomeViewModel =
        HomeViewModel(
            appContext,
            recordingRepository,
            recordingManagerOverride,
            tagRepository,
            profileRepository,
            transcriptionQueueManager,
            recordingTextEnrichment,
            audioImportOrchestrator,
            sessionRecovery,
            playbackController = playbackControllerOverride,
            savedStateHandle = savedStateHandle,
            defaultDispatcher = testDispatcher,
            serviceEvents = serviceEvents,
        )

    private fun samplePreview(
        recordingId: UUID,
        previewText: String,
        summary: String?,
    ): TranscriptPreview =
        TranscriptPreview(
            recordingId = recordingId,
            summary = summary,
            previewText = previewText,
        )
}

/** Test-side mirror of HomeViewModel.SEARCH_DEBOUNCE_MS (private in production). */
private const val SEARCH_DEBOUNCE_MS = 200L
