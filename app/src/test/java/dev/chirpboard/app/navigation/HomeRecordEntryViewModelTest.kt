package dev.chirpboard.app.navigation

import app.cash.turbine.test
import dev.chirpboard.app.download.ModelReadinessGate
import dev.chirpboard.app.download.ModelReadinessState
import dev.chirpboard.app.download.ModelReadinessUnavailableReason
import dev.chirpboard.app.download.ModelReadyResult
import dev.chirpboard.app.download.ModelReadinessState.Checking
import dev.chirpboard.app.download.ModelReadySource
import dev.chirpboard.app.download.VerificationTrigger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRecordEntryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var modelReadinessGate: ModelReadinessGate
    private lateinit var readinessStateFlow: MutableStateFlow<ModelReadinessState>
    private lateinit var viewModel: HomeRecordEntryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        modelReadinessGate = mockk(relaxed = true)
        readinessStateFlow = MutableStateFlow(ModelReadinessState.Unknown)
        every { modelReadinessGate.state } returns readinessStateFlow
        viewModel = HomeRecordEntryViewModel(modelReadinessGate)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `warmupOnHomeVisible calls gate warmup`() {
        viewModel.warmupOnHomeVisible()
        verify { modelReadinessGate.warmupIfNeeded(VerificationTrigger.HOME_VISIBLE) }
    }

    @Test
    fun `onRecordTapped ignores tap when checking`() = runTest {
        readinessStateFlow.value = Checking()
        viewModel.onRecordTapped()
        
        viewModel.events.test {
            expectNoEvents()
            cancelAndIgnoreEvents()
        }

        coVerify(exactly = 0) { modelReadinessGate.ensureReady(any()) }
    }

    @Test
    fun `onRecordTapped emits NavigateToRecord when ready`() = runTest {
        readinessStateFlow.value = ModelReadinessState.Ready(ModelReadySource.DISK_CACHE)
        coEvery { modelReadinessGate.ensureReady(VerificationTrigger.HOME_RECORD_TAP) } returns ModelReadyResult.Ready(ModelReadySource.DISK_CACHE)

        viewModel.onRecordTapped()
        
        viewModel.events.test {
            assertEquals(HomeRecordEntryEvent.NavigateToRecord, awaitItem())
            cancelAndIgnoreEvents()
        }
    }

    @Test
    fun `onRecordTapped emits ShowModelRequired when unavailable`() = runTest {
        readinessStateFlow.value = ModelReadinessState.Unavailable(ModelReadinessUnavailableReason.MODEL_MISSING_OR_CORRUPT)
        coEvery { modelReadinessGate.ensureReady(VerificationTrigger.HOME_RECORD_TAP) } returns ModelReadyResult.Unavailable(ModelReadinessUnavailableReason.MODEL_MISSING_OR_CORRUPT)


        viewModel.onRecordTapped()

        viewModel.events.test {
            assertEquals(HomeRecordEntryEvent.ShowModelRequired(ModelReadinessUnavailableReason.MODEL_MISSING_OR_CORRUPT), awaitItem())
            cancelAndIgnoreEvents()
        }
    }

    @Test
    fun `onRecordTapped emits ShowError on failure`() = runTest {
        readinessStateFlow.value = ModelReadinessState.Unknown
        coEvery { modelReadinessGate.ensureReady(VerificationTrigger.HOME_RECORD_TAP) } returns ModelReadyResult.Error("Test error")

        viewModel.onRecordTapped()

        viewModel.events.test {
            assertEquals(HomeRecordEntryEvent.ShowError("Test error"), awaitItem())
            cancelAndIgnoreEvents()
        }
    }
}
