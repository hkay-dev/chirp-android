package dev.chirpboard.app.feature.transcription.settings

import android.content.Context
import app.cash.turbine.test
import dev.chirpboard.app.feature.transcription.WhisperModelManager
import dev.chirpboard.app.feature.transcription.WhisperModelManager.ModelStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
    private lateinit var mockContext: Context
    private lateinit var mockModelManager: WhisperModelManager
    private lateinit var mockStatusFlow: MutableStateFlow<ModelStatus>
    private lateinit var viewModel: TranscriptionSettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockContext = mockk(relaxed = true)
        mockModelManager = mockk(relaxed = true)
        mockStatusFlow = MutableStateFlow(ModelStatus.NotDownloaded)
        every { mockModelManager.modelStatus } returns mockStatusFlow
        
        viewModel = TranscriptionSettingsViewModel(mockContext, mockModelManager)
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
            
            mockStatusFlow.value = ModelStatus.Downloading(0.5f)
            state = awaitItem()
            assertTrue(state.isLoading)
            assertEquals(0.5f, state.downloadProgress, 0.001f)
            
            mockStatusFlow.value = ModelStatus.Error("Test error")
            state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals("Test error", state.errorMessage)
            
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
            
            verify { mockModelManager.deleteModel() }
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
