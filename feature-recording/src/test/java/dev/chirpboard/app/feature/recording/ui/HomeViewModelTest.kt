package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.llm.RecordingTextEnrichment
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.TranscriptPreview
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.recording.importing.AudioImportOrchestrator
import dev.chirpboard.app.feature.recording.importing.AudioImportResult
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var recordingManager: RecordingManager
    private lateinit var tagRepository: TagRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var transcriptionQueueManager: TranscriptionRecovery
    private lateinit var recordingTextEnrichment: RecordingTextEnrichment
    private lateinit var audioImportOrchestrator: AudioImportOrchestrator
    private lateinit var sessionRecovery: RecordingRecoveryStore
    private lateinit var savedStateHandle: SavedStateHandle

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
            val recording =
                mockk<Recording>(relaxed = true) {
                    every { id } returns recordingId
                    every { status } returns RecordingStatus.FAILED
                }

            val displayItem =
                mockk<dev.chirpboard.app.feature.recording.ui.RecordingDisplayItem>(relaxed = true) {
                    every { this@mockk.id } returns recordingId
                    every { this@mockk.status } returns RecordingStatus.FAILED
                    every { this@mockk.recording } returns recording
                }

            viewModel.retryTranscription(displayItem)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transcriptionQueueManager.retry(recordingId) }
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
        runBlocking {
            Dispatchers.setMain(Dispatchers.Unconfined)
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
            coEvery { tagRepository.getTagsForRecordingIds(any()) } returns emptyMap()
            coEvery { recordingRepository.getTranscripts(any()) } returns emptyMap()

            val localViewModel =
                HomeViewModel(
                    recordingRepository,
                    localRecordingManager,
                    tagRepository,
                    profileRepository,
                    transcriptionQueueManager,
                    recordingTextEnrichment,
                    audioImportOrchestrator,
                    sessionRecovery,
                    playbackController =
                        mockk<dev.chirpboard.app.core.playback.RecordingPlaybackController>(relaxed = true) {
                            every { state } returns MutableStateFlow(dev.chirpboard.app.core.playback.RecordingPlaybackState())
                        },
                    savedStateHandle = SavedStateHandle(),
                )
            val collector = launch { localViewModel.displayItems.collect { } }

            localViewModel.onSearchQueryChange("team")
            delay(300)

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
        runBlocking {
            Dispatchers.setMain(Dispatchers.Unconfined)
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
            coEvery { tagRepository.getTagsForRecordingIds(any()) } returns emptyMap()
            coEvery { recordingRepository.getTranscripts(any()) } returns emptyMap()

            val localViewModel =
                HomeViewModel(
                    recordingRepository,
                    recordingManager,
                    tagRepository,
                    profileRepository,
                    transcriptionQueueManager,
                    recordingTextEnrichment,
                    audioImportOrchestrator,
                    sessionRecovery,
                    playbackController =
                        mockk<dev.chirpboard.app.core.playback.RecordingPlaybackController>(relaxed = true) {
                            every { state } returns MutableStateFlow(dev.chirpboard.app.core.playback.RecordingPlaybackState())
                        },
                    savedStateHandle = SavedStateHandle(),
                )

            val collector = launch { localViewModel.displayItems.collect { } }
            delay(300)

            assertEquals(listOf(recordingId), localViewModel.displayItems.value.map { it.id })
            assertFalse(localViewModel.displayItems.value.single().isLiveCapture)
            collector.cancel()
        }

    @Test
    fun `displayItems includes finalizing recording while stop is in progress`() =
        runBlocking {
            Dispatchers.setMain(Dispatchers.Unconfined)
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
            coEvery { tagRepository.getTagsForRecordingIds(any()) } returns emptyMap()
            coEvery { recordingRepository.getTranscripts(any()) } returns emptyMap()

            val localViewModel =
                HomeViewModel(
                    recordingRepository,
                    recordingManager,
                    tagRepository,
                    profileRepository,
                    transcriptionQueueManager,
                    recordingTextEnrichment,
                    audioImportOrchestrator,
                    sessionRecovery,
                    playbackController =
                        mockk<dev.chirpboard.app.core.playback.RecordingPlaybackController>(relaxed = true) {
                            every { state } returns MutableStateFlow(dev.chirpboard.app.core.playback.RecordingPlaybackState())
                        },
                    savedStateHandle = SavedStateHandle(),
                )

            val collector = launch { localViewModel.displayItems.collect { } }
            delay(300)

            assertEquals(listOf(recordingId), localViewModel.displayItems.value.map { it.id })
            collector.cancel()
        }

    @Test
    fun `shouldShowRecordingOnHomeList includes live capture row`() {
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
            shouldShowRecordingOnHomeList(
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
        assertTrue(
            shouldShowRecordingOnHomeList(
                recording,
                RecordingState.Stopping(
                    origin = dev.chirpboard.app.core.recording.RecordingOrigin.APP,
                    recordingId = recordingId,
                ),
            ),
        )
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

    private fun createHomeViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        recordingManagerOverride: RecordingManager = recordingManager,
    ): HomeViewModel =
        HomeViewModel(
            recordingRepository,
            recordingManagerOverride,
            tagRepository,
            profileRepository,
            transcriptionQueueManager,
            recordingTextEnrichment,
            audioImportOrchestrator,
            sessionRecovery,
            playbackController =
                mockk<dev.chirpboard.app.core.playback.RecordingPlaybackController>(relaxed = true) {
                    every { state } returns MutableStateFlow(dev.chirpboard.app.core.playback.RecordingPlaybackState())
                },
            savedStateHandle = savedStateHandle,
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
