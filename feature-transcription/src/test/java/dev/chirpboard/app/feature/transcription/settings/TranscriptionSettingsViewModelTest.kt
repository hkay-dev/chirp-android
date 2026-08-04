package dev.chirpboard.app.feature.transcription.settings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.chirpboard.app.feature.transcription.SpeechModelManager
import dev.chirpboard.app.feature.transcription.SpeechModelManager.ModelStatus
import dev.chirpboard.app.core.transcription.CloudFileTranscriptionProvider
import dev.chirpboard.app.core.transcription.CloudTranscriptionConfigurationStatus
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.core.transcription.LocalSpeechModelCatalog
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockModelManager: SpeechModelManager
    private lateinit var mockStatusFlow: MutableStateFlow<ModelStatus>
    private lateinit var selectedModel: MutableStateFlow<LocalSpeechModelId>
    private lateinit var managedModel: MutableStateFlow<LocalSpeechModelId>
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: TranscriptionSettingsViewModel
    private lateinit var routingStore: TranscriptionRoutingStore
    private lateinit var selectedEngine: MutableStateFlow<TranscriptionEngine>
    private lateinit var cloudTranscriber: CloudFileTranscriptionProvider

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockModelManager = mockk(relaxed = true)
        mockStatusFlow = MutableStateFlow(ModelStatus.NotDownloaded)
        selectedModel = MutableStateFlow(LocalSpeechModelId.PARAKEET_TDT_600M)
        managedModel = MutableStateFlow(LocalSpeechModelId.PARAKEET_TDT_600M)
        every { mockModelManager.modelStatus } returns mockStatusFlow
        every { mockModelManager.availableModels } returns LocalSpeechModelCatalog.models
        every { mockModelManager.selectedModel } returns selectedModel
        every { mockModelManager.managedModel } returns managedModel
        every { mockModelManager.modelInfo(any()) } answers {
            LocalSpeechModelCatalog.requireModel(firstArg())
        }
        coEvery { mockModelManager.getDownloadedSize() } returns 0L

        savedStateHandle = SavedStateHandle()
        routingStore = mockk(relaxed = true)
        selectedEngine = MutableStateFlow(TranscriptionEngine.LOCAL_PARAKEET)
        every { routingStore.selectedEngine } returns selectedEngine
        cloudTranscriber = mockk()
        coEvery { cloudTranscriber.configurationStatus() } returns
            CloudTranscriptionConfigurationStatus.AUTHENTICATION_MISSING

        viewModel =
            TranscriptionSettingsViewModel(
                mockModelManager,
                savedStateHandle,
                routingStore,
                cloudTranscriber,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init refreshes status and sets up flow collection`() = runTest {
        verify { mockModelManager.refreshStatus() }

        viewModel.uiState.test {
            // Initial state from flow
            var state = awaitItem()
            assertFalse(state.isDownloaded)
            assertFalse(state.isLoading)

            // Update flow
            mockStatusFlow.value = ModelStatus.Ready
            state = awaitItem()
            assertTrue(state.isDownloaded)
            assertFalse(state.isLoading)

            mockStatusFlow.value = ModelStatus.Downloading(0.5f, "encoder.int8.onnx")
            state = awaitItem()
            assertTrue(state.isLoading)
            assertEquals(0.5f, state.downloadProgress, 0.001f)
            assertEquals("encoder.int8.onnx", state.currentFile)

            mockStatusFlow.value = ModelStatus.WaitingForNetwork
            state = awaitItem()
            assertTrue(state.isLoading)
            assertTrue(state.isWaitingForNetwork)

            mockStatusFlow.value = ModelStatus.Error("Test error")
            state = awaitItem()
            assertFalse(state.isLoading)
            assertFalse(state.isWaitingForNetwork)
            assertEquals("Test error", state.errorMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `downloadModel delegates to the app-scoped download work`() = runTest {
        viewModel.downloadModel()

        verify { mockModelManager.requestDownload(preferInternalStorage = false) }
    }

    @Test
    fun `downloadModel forwards the internal-storage choice`() = runTest {
        viewModel.downloadModel(preferInternalStorage = true)

        verify { mockModelManager.requestDownload(preferInternalStorage = true) }
    }

    @Test
    fun `failed cloud auth check is shown as temporary instead of a missing endpoint`() =
        runTest {
            coEvery { cloudTranscriber.configurationStatus() } throws IllegalStateException("auth service unavailable")

            viewModel.refreshCloudConfiguration()
            advanceUntilIdle()

            assertEquals(
                CloudTranscriptionConfigurationStatus.TEMPORARILY_UNAVAILABLE,
                viewModel.uiState.value.cloudConfigurationStatus,
            )
        }

    @Test
    fun `cancelDownload delegates to the model manager`() = runTest {
        viewModel.cancelDownload()

        verify { mockModelManager.cancelDownload() }
    }

    @Test
    fun `selectEngine saves the cloud engine`() = runTest {
        viewModel.selectEngine(TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)
        advanceUntilIdle()

        coVerify { routingStore.setSelectedEngine(TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3) }
    }

    // LIF-06/ERR-3: the autoDownload nav-arg fires exactly once — re-running the effect
    // after rotation, process-death restore, or an error->idle flip must be a no-op.
    @Test
    fun `consumePendingAutoDownload consumes the nav-arg exactly once`() = runTest {
        savedStateHandle[TranscriptionSettingsViewModel.KEY_AUTO_DOWNLOAD] = true

        assertTrue(viewModel.consumePendingAutoDownload())
        assertFalse(viewModel.consumePendingAutoDownload())
        assertEquals(
            true,
            savedStateHandle.get<Boolean>(TranscriptionSettingsViewModel.KEY_AUTO_DOWNLOAD_CONSUMED),
        )
    }

    @Test
    fun `consumePendingAutoDownload is false without the nav-arg`() = runTest {
        assertFalse(viewModel.consumePendingAutoDownload())
    }

    @Test
    fun `consumePendingAutoDownload stays consumed across process death`() = runTest {
        // A restored SavedStateHandle carries both the nav-arg and the consumed flag.
        savedStateHandle[TranscriptionSettingsViewModel.KEY_AUTO_DOWNLOAD] = true
        savedStateHandle[TranscriptionSettingsViewModel.KEY_AUTO_DOWNLOAD_CONSUMED] = true

        assertFalse(viewModel.consumePendingAutoDownload())
    }

    // PLT-07: returning from the All-files-access settings page with the grant resumes
    // the download the user asked for.
    @Test
    fun `onResumed starts the download after the grant was given`() = runTest {
        viewModel.onAllFilesAccessRequested()

        viewModel.onResumed(hasAllFilesAccess = true)

        verify { mockModelManager.requestDownload(preferInternalStorage = false) }
        assertEquals(
            false,
            savedStateHandle.get<Boolean>(TranscriptionSettingsViewModel.KEY_AWAITING_ALL_FILES_GRANT),
        )
    }

    @Test
    fun `onResumed does nothing when the grant was declined`() = runTest {
        viewModel.onAllFilesAccessRequested()

        viewModel.onResumed(hasAllFilesAccess = false)

        verify(exactly = 0) { mockModelManager.requestDownload(any()) }
    }

    @Test
    fun `onResumed without a pending grant request is a no-op`() = runTest {
        viewModel.onResumed(hasAllFilesAccess = true)

        verify(exactly = 0) { mockModelManager.requestDownload(any()) }
    }

    @Test
    fun `storage choice dialog state toggles`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial
            viewModel.showStorageChoice()
            assertTrue(awaitItem().showStorageChoice)
            viewModel.dismissStorageChoice()
            assertFalse(awaitItem().showStorageChoice)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `showDeleteConfirmation updates uiState`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial
            viewModel.showDeleteConfirmation()
            val state = awaitItem()
            assertTrue(state.showDeleteConfirmation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissDeleteConfirmation updates uiState`() = runTest {
        viewModel.showDeleteConfirmation()
        viewModel.uiState.test {
            awaitItem() // current
            viewModel.dismissDeleteConfirmation()
            val state = awaitItem()
            assertFalse(state.showDeleteConfirmation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteModel calls modelManager and updates state on success`() = runTest {
        viewModel.showDeleteConfirmation()
        coEvery { mockModelManager.deleteModel() } returns true

        viewModel.uiState.test {
            awaitItem() // current with show=true
            viewModel.deleteModel()

            val state = awaitItem()
            assertFalse(state.showDeleteConfirmation)
            assertNull(state.errorMessage)

            coVerify { mockModelManager.deleteModel() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteModel updates state with error on failure`() = runTest {
        coEvery { mockModelManager.deleteModel() } returns false

        viewModel.uiState.test {
            awaitItem() // initial
            viewModel.deleteModel()

            val state1 = awaitItem() // error and dismissed confirmation
            assertEquals("Failed to delete model files", state1.errorMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissError sets errorMessage to null`() = runTest {
        mockStatusFlow.value = ModelStatus.Error("Some error")

        viewModel.uiState.test {
            awaitItem() // initial
            val stateWithError = awaitItem()
            assertEquals("Some error", stateWithError.errorMessage)

            viewModel.dismissError()
            val stateResolved = awaitItem()
            assertNull(stateResolved.errorMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
