package dev.chirpboard.app.navigation

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.feature.recording.importing.AudioImportOrchestrator
import dev.chirpboard.app.feature.recording.importing.AudioImportResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAudioHandoffViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var audioImportOrchestrator: AudioImportOrchestrator
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: SharedAudioHandoffViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        audioImportOrchestrator = mockk()
        savedStateHandle = SavedStateHandle()
        viewModel = SharedAudioHandoffViewModel(audioImportOrchestrator, savedStateHandle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cold launch request imports and navigates once`() =
        runTest {
            val uri = mockk<Uri>()
            val recordingId = UUID.randomUUID()
            val request = SharedAudioRequest(token = "share-1", uri = uri)

            coEvery { audioImportOrchestrator.import(uri) } returns AudioImportResult.SavedAndQueued(recordingId)

            viewModel.onIncomingRequest(request)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(SharedAudioIntakeState.Idle, viewModel.uiState.value)
            assertEquals(recordingId, viewModel.navigationTarget.value)
            coVerify(exactly = 1) { audioImportOrchestrator.import(uri) }
        }

    @Test
    fun `duplicate request token is ignored after success`() =
        runTest {
            val uri = mockk<Uri>()
            val recordingId = UUID.randomUUID()
            val request = SharedAudioRequest(token = "share-2", uri = uri)

            coEvery { audioImportOrchestrator.import(uri) } returns AudioImportResult.SavedAndQueued(recordingId)

            viewModel.onIncomingRequest(request)
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.onNavigationHandled()

            viewModel.onIncomingRequest(request)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { audioImportOrchestrator.import(uri) }
        }

    @Test
    fun `warm launch request starts a new intake after the first one finishes`() =
        runTest {
            val firstUri = mockk<Uri>()
            val secondUri = mockk<Uri>()

            coEvery { audioImportOrchestrator.import(firstUri) } returns AudioImportResult.SavedAndQueued(UUID.randomUUID())
            coEvery { audioImportOrchestrator.import(secondUri) } returns AudioImportResult.SavedAndQueued(UUID.randomUUID())

            viewModel.onIncomingRequest(SharedAudioRequest(token = "share-3", uri = firstUri))
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.onNavigationHandled()

            viewModel.onIncomingRequest(SharedAudioRequest(token = "share-4", uri = secondUri))
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { audioImportOrchestrator.import(firstUri) }
            coVerify(exactly = 1) { audioImportOrchestrator.import(secondUri) }
        }

    @Test
    fun `failed intake can retry and recover`() =
        runTest {
            val uri = mockk<Uri>()
            val recordingId = UUID.randomUUID()
            val request = SharedAudioRequest(token = "share-5", uri = uri)

            coEvery { audioImportOrchestrator.import(uri) } returnsMany
                listOf(
                    AudioImportResult.FailedBeforePersistence("Couldn't copy the shared audio file."),
                    AudioImportResult.SavedAndQueued(recordingId),
                )

            viewModel.onIncomingRequest(request)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value is SharedAudioIntakeState.Failure)

            viewModel.retry()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(SharedAudioIntakeState.Idle, viewModel.uiState.value)
            assertEquals(recordingId, viewModel.navigationTarget.value)
            coVerify(exactly = 2) { audioImportOrchestrator.import(uri) }
        }
}
