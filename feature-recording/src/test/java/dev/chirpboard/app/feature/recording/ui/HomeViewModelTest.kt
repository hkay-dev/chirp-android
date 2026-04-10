package dev.chirpboard.app.feature.recording.ui

import dev.chirpboard.app.data.model.RecordingStatus
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.transcription.TranscriptionQueueManager
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

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var recordingManager: RecordingManager
    private lateinit var tagRepository: TagRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var transcriptionQueueManager: TranscriptionQueueManager
    private lateinit var llmClient: LlmClient
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
        llmClient = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()

        viewModel =
            HomeViewModel(
                recordingRepository,
                recordingManager,
                tagRepository,
                profileRepository,
                transcriptionQueueManager,
                llmClient,
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
    fun `deleteRecording delegates to repository and file system`() =
        runTest {
            val recording =
                mockk<Recording>(relaxed = true) {
                    every { id } returns UUID.randomUUID()
                    every { audioPath } returns "/fake/path.wav"
                }

            viewModel.deleteRecording(recording)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { recordingRepository.delete(recording) }
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

            viewModel.retryTranscription(recording)
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

            viewModel.recoverStuckItem(recording)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transcriptionQueueManager.recoverPendingTranscription(recordingId) }
        }
}
