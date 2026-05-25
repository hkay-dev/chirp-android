package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.core.llm.RecordingTextEnrichment
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.recording.importing.AudioImportOrchestrator
import dev.chirpboard.app.feature.recording.importing.AudioImportResult
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.Date

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
            }
        recordingManager =
            mockk(relaxed = true) {
                every { state } returns MutableStateFlow(RecordingState.Idle)
            }
        tagRepository =
            mockk(relaxed = true) {
                every { getAllTags() } returns emptyFlow()
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
        coEvery { sessionRecovery.refresh() } returns Unit
        val playbackController =
            mockk<dev.chirpboard.app.core.audio.RecordingPlaybackController>(relaxed = true) {
                every { state } returns MutableStateFlow(dev.chirpboard.app.core.audio.RecordingPlaybackState())
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
    fun `deleteRecording delegates to repository and file system`() =
        runTest {
            val recording =
                Recording(
                    id = UUID.randomUUID(),
                    title = "Test",
                    audioPath = "/fake/path.wav",
                    source = RecordingSource.APP,
                )

            val displayItem = RecordingDisplayItem(recording = recording)

            viewModel.deleteRecording(displayItem)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { recordingRepository.deleteById(recording.id) }
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
        }
}
