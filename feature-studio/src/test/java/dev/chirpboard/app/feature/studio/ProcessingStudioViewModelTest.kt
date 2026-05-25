package dev.chirpboard.app.feature.studio

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.playback.RecordingPlaybackController
import dev.chirpboard.app.core.playback.RecordingPlaybackState
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.RecoveryOwnershipState
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingStudioViewModelTest {
    private lateinit var repository: RecordingRepository
    private lateinit var transcriptionRecovery: TranscriptionRecovery
    private lateinit var context: Context

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        transcriptionRecovery = mockk(relaxed = true)
        coEvery { transcriptionRecovery.getRecoveryDiagnostics(any()) } returns
            RecoveryDiagnostics(
                latestReason = null,
                lastAttemptEpochMs = null,
                ownership = RecoveryOwnershipState.MISSING_OR_TERMINAL,
            )
        context = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `invalid recording id marks InvalidId without subscribing`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val viewModel = createViewModel(recordingId = "not-a-uuid")
            advanceUntilIdle()

            assertEquals(ProcessingStudioLoadState.InvalidId, viewModel.uiState.value.loadState)
        }

    @Test
    fun `missing recording row transitions to NotFound after grace period`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            stubEmptyRecordingFlows(recordingId)
            coEvery { repository.getRecording(recordingId) } returns null

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()
            advanceTimeBy(MISSING_RECORDING_GRACE_MS)
            advanceUntilIdle()

            assertEquals(ProcessingStudioLoadState.NotFound, viewModel.uiState.value.loadState)
        }

    @Test
    fun `deleted recording while observing transitions to NotFound immediately`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val recording = sampleRecording(recordingId)
            val recordingFlow = MutableStateFlow<RepositoryFlowState<Recording?>>(RepositoryFlowState(recording))
            every { repository.getRecordingFlow(recordingId) } returns recordingFlow
            stubSupportingFlows(recordingId)

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()
            assertEquals(ProcessingStudioLoadState.Ready, viewModel.uiState.value.loadState)

            recordingFlow.value = RepositoryFlowState(null)
            advanceUntilIdle()

            assertEquals(ProcessingStudioLoadState.NotFound, viewModel.uiState.value.loadState)
        }

    private fun createViewModel(recordingId: String): ProcessingStudioViewModel {
        val llmPreferences =
            mockk<LlmPreferences>(relaxed = true) {
                every { llmEnabled } returns MutableStateFlow(false)
                every { hasApiKey() } returns false
            }
        val playbackController =
            mockk<RecordingPlaybackController>(relaxed = true) {
                every { state } returns MutableStateFlow(RecordingPlaybackState())
            }
        return ProcessingStudioViewModel(
            context = context,
            savedStateHandle = SavedStateHandle(mapOf("recordingId" to recordingId)),
            repository = repository,
            llmClient = mockk(relaxed = true),
            llmPreferences = llmPreferences,
            wordReplacementRepository = mockk(relaxed = true),
            transcriptionRecovery = transcriptionRecovery,
            playbackController = playbackController,
        )
    }

    private fun stubEmptyRecordingFlows(recordingId: UUID) {
        every { repository.getRecordingFlow(recordingId) } returns flowOf(RepositoryFlowState(null))
        stubSupportingFlows(recordingId)
    }

    private fun stubSupportingFlows(recordingId: UUID) {
        every { repository.getTranscriptFlow(recordingId) } returns flowOf(RepositoryFlowState(null))
        every { repository.getTranscriptTimingsFlow(recordingId) } returns flowOf(RepositoryFlowState(emptyList()))
        every { repository.getStructuredOutcomeSnapshotFlow(recordingId) } returns flowOf(RepositoryFlowState(null))
    }

    private fun sampleRecording(id: UUID): Recording =
        Recording(
            id = id,
            title = "Meeting",
            audioPath = "/tmp/test.m4a",
            status = RecordingStatus.COMPLETED,
            source = RecordingSource.APP,
            createdAt = Date(),
            durationMs = 1_000L,
        )
}
