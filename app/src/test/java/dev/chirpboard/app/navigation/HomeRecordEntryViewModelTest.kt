package dev.chirpboard.app.navigation

import app.cash.turbine.test
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.ModelReadyResult
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.feature.recording.RecordingManager
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

import java.util.UUID
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRecordEntryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var modelReadinessGate: SpeechModelReadinessGate
    private lateinit var recordingManager: RecordingManager
    private lateinit var readinessStateFlow: MutableStateFlow<ModelReadinessState>
    private lateinit var recordingStateFlow: MutableStateFlow<RecordingState>
    private lateinit var viewModel: HomeRecordEntryViewModel

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        Dispatchers.setMain(testDispatcher)
        modelReadinessGate = mockk(relaxed = true)
        recordingManager = mockk(relaxed = true)
        readinessStateFlow = MutableStateFlow(ModelReadinessState.Unknown)
        recordingStateFlow = MutableStateFlow(RecordingState.Idle)
        every { modelReadinessGate.state } returns readinessStateFlow
        every { recordingManager.state } returns recordingStateFlow
        every { recordingManager.hasActiveAppCapture } returns false
        viewModel = HomeRecordEntryViewModel(modelReadinessGate, recordingManager)
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
    fun `onRecordTapped navigates to active record screen when app capture is live`() = runTest {
        every { recordingManager.hasActiveAppCapture } returns true

        viewModel.onRecordTapped()

        viewModel.events.test {
            assertEquals(HomeRecordEntryEvent.NavigateToRecord(autoStart = false), awaitItem())
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
            assertEquals(HomeRecordEntryEvent.NavigateToRecord(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onRecordTapped keeps selected profile id when ready`() = runTest {
        val profileId = UUID.randomUUID()
        readinessStateFlow.value = ModelReadinessState.Ready(System.currentTimeMillis(), ModelReadinessVerificationSource.PROCESS_CACHE)
        coEvery { modelReadinessGate.ensureReady(VerificationTrigger.HOME_RECORD_TAP) } returns ModelReadyResult.Ready(ModelReadinessVerificationSource.PROCESS_CACHE)

        viewModel.onRecordTapped(profileId)

        viewModel.events.test {
            assertEquals(HomeRecordEntryEvent.NavigateToRecord(profileId), awaitItem())
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
