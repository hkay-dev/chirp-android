package dev.chirpboard.app.navigation

import app.cash.turbine.test
import dev.chirpboard.app.download.ModelReadinessGate
import dev.chirpboard.app.download.ModelReadinessState
import dev.chirpboard.app.download.ModelReadinessUnavailableReason
import dev.chirpboard.app.download.ModelReadyResult
import dev.chirpboard.app.download.ModelReadinessVerificationSource
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
import android.util.Log
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRecordEntryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var modelReadinessGate: ModelReadinessGate
    private lateinit var readinessStateFlow: MutableStateFlow<ModelReadinessState>
    private lateinit var viewModel: HomeRecordEntryViewModel

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        Dispatchers.setMain(testDispatcher)
        modelReadinessGate = mockk(relaxed = true)
        readinessStateFlow = MutableStateFlow(ModelReadinessState.Unknown)
        every { modelReadinessGate.state } returns readinessStateFlow
        viewModel = HomeRecordEntryViewModel(modelReadinessGate)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `warmupOnHomeVisible calls gate warmup`() {
        viewModel.warmupOnHomeVisible()
        verify { modelReadinessGate.warmupIfNeeded(VerificationTrigger.HOME_VISIBLE) }
    }

    @Test
    fun `onRecordTapped ignores tap when checking`() = runTest {
        readinessStateFlow.value = ModelReadinessState.Checking(VerificationTrigger.HOME_VISIBLE, System.currentTimeMillis())
        viewModel.onRecordTapped()
        
        viewModel.events.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { modelReadinessGate.ensureReady(any()) }
    }

    @Test
    fun `onRecordTapped emits NavigateToRecord when ready`() = runTest {
        readinessStateFlow.value = ModelReadinessState.Ready(System.currentTimeMillis(), ModelReadinessVerificationSource.PROCESS_CACHE)
        coEvery { modelReadinessGate.ensureReady(VerificationTrigger.HOME_RECORD_TAP) } returns ModelReadyResult.Ready(ModelReadinessVerificationSource.PROCESS_CACHE)

        viewModel.onRecordTapped()
        
        viewModel.events.test {
            assertEquals(HomeRecordEntryEvent.NavigateToRecord, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onRecordTapped emits ShowModelRequired when unavailable`() = runTest {
        readinessStateFlow.value = ModelReadinessState.Unavailable(ModelReadinessUnavailableReason.MISSING_MODEL_FILES)
        coEvery { modelReadinessGate.ensureReady(VerificationTrigger.HOME_RECORD_TAP) } returns ModelReadyResult.Unavailable(ModelReadinessUnavailableReason.MISSING_MODEL_FILES)


        viewModel.onRecordTapped()

        viewModel.events.test {
            assertEquals(HomeRecordEntryEvent.ShowModelRequired(ModelReadinessUnavailableReason.MISSING_MODEL_FILES), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onRecordTapped emits ShowError on failure`() = runTest {
        readinessStateFlow.value = ModelReadinessState.Unknown
        coEvery { modelReadinessGate.ensureReady(VerificationTrigger.HOME_RECORD_TAP) } returns ModelReadyResult.Error("Test error")

        viewModel.onRecordTapped()

        viewModel.events.test {
            assertEquals(HomeRecordEntryEvent.ShowError("Test error"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
