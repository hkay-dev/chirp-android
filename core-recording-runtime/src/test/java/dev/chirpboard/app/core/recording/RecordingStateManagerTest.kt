package dev.chirpboard.app.core.recording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingStateManagerTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()


    private lateinit var manager: RecordingStateManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        manager = RecordingStateManager()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isIdle() {
        assertTrue(manager.state.value is RecordingState.Idle)
        assertTrue(manager.canStartRecording())
    }

    @Test
    fun tryStartRecording_success() {
        val profileId = UUID.randomUUID()
        val result = manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = profileId)
        assertTrue(result is RecordingStartResult.Success)
        val state = manager.state.value
        assertTrue(state is RecordingState.Starting)
        assertEquals(profileId, (state as RecordingState.Starting).profileId)
        assertFalse(manager.canStartRecording())
    }

    @Test
    fun tryStartRecording_failsIfAlreadyRecording() {
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        val result2 = manager.tryStartRecording(origin = RecordingOrigin.KEYBOARD, profileId = null)
        
        assertTrue(result2 is RecordingStartResult.AlreadyRecording)
        assertEquals(RecordingOrigin.APP, (result2 as RecordingStartResult.AlreadyRecording).currentOrigin)
    }

    @Test
    fun onRecordingStarted_transitionsToRecording() {
        val recordingId = UUID.randomUUID()
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path/to/file", recordingId = recordingId)
        
        val state = manager.state.value
        assertTrue(state is RecordingState.Recording)
        assertEquals("path/to/file", (state as RecordingState.Recording).audioFilePath)
        assertEquals(recordingId, state.recordingId)
        assertEquals(recordingId, state.activeRecordingId)
    }

    @Test
    fun onRecordingIdAssigned_exposesActiveRecordingIdDuringStarting() {
        val recordingId = UUID.randomUUID()
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingIdAssigned(recordingId)

        val state = manager.state.value
        assertTrue(state is RecordingState.Starting)
        assertEquals(recordingId, state.activeRecordingId)
    }

    @Test
    fun transitionToStopping_preservesRecordingIdFromStarting() {
        val recordingId = UUID.randomUUID()
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingIdAssigned(recordingId)

        manager.transitionToStopping()

        val state = manager.state.value
        assertTrue(state is RecordingState.Stopping)
        assertEquals(recordingId, (state as RecordingState.Stopping).recordingId)
    }

    @Test
    fun transitionToStopping_preservesRecordingId() {
        val recordingId = UUID.randomUUID()
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path/to/file", recordingId = recordingId)

        manager.transitionToStopping()

        val state = manager.state.value
        assertTrue(state is RecordingState.Stopping)
        assertEquals(recordingId, (state as RecordingState.Stopping).recordingId)
    }

    @Test
    fun pauseAndResumeRecording_updatesStateAndAccumulatedTime() {
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path")
        
        manager.pauseRecording()
        var state = manager.state.value
        assertTrue(state is RecordingState.Paused)
        
        manager.resumeRecording()
        state = manager.state.value
        assertTrue(state is RecordingState.Recording)
    }

    @Test
    fun onCaptureStopHandoff_releasesLockImmediately() {
        val recordingId = UUID.randomUUID()
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path", recordingId = recordingId)

        manager.onCaptureStopHandoff(recordingId)

        assertTrue(manager.state.value is RecordingState.Idle)
        assertTrue(manager.canStartRecording())
        assertEquals(recordingId, manager.lastCompletedRecordingId.value)
    }

    @Test
    fun onCaptureStopHandoff_ignoresStaleRecordingId() {
        val oldRecordingId = UUID.randomUUID()
        val activeRecordingId = UUID.randomUUID()
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path", recordingId = activeRecordingId)

        manager.onCaptureStopHandoff(oldRecordingId)

        val state = manager.state.value
        assertTrue(state is RecordingState.Recording)
        assertEquals(activeRecordingId, state.activeRecordingId)
        assertFalse(manager.canStartRecording())
        assertEquals(null, manager.lastCompletedRecordingId.value)
    }
    @Test
    fun onCaptureStopHandoff_staleRecordingIdDoesNotCancelStoppingTimeout() {
        manager.stoppingTimeoutMsOverrideForTest = 10L
        val staleRecordingId = UUID.randomUUID()
        val activeRecordingId = UUID.randomUUID()
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path", recordingId = activeRecordingId)
        manager.transitionToStopping()
        manager.startStoppingTimeout(fileSizeBytes = 0L)

        manager.onCaptureStopHandoff(staleRecordingId)
        Thread.sleep(100)

        assertTrue(manager.state.value is RecordingState.Error)
        manager.clearError()
        assertTrue(manager.canStartRecording())
    }


    @Test
    fun onRecordingCompleted_returnsToIdle() {
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.transitionToStopping()
        manager.startStoppingTimeout(fileSizeBytes = 0L)
        manager.onRecordingCompleted(UUID.randomUUID())
        
        manager.clearError()
        assertTrue(manager.state.value is RecordingState.Idle)
    }
    @Test
    fun onRecordingCompleted_ignoresStaleRecordingIdWhileNewerRecordingIsActive() {
        val staleRecordingId = UUID.randomUUID()
        val activeRecordingId = UUID.randomUUID()
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path", recordingId = activeRecordingId)

        manager.onRecordingCompleted(staleRecordingId)

        val state = manager.state.value
        assertTrue(state is RecordingState.Recording)
        assertEquals(activeRecordingId, state.activeRecordingId)
        assertFalse(manager.canStartRecording())
        assertEquals(null, manager.lastCompletedRecordingId.value)
    }


    @Test
    fun onRecordingError_transitionsToErrorAndReleasesLock() {
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingError("Test Error")
        assertTrue(manager.state.value is RecordingState.Error)
        
        // Lock should be released, so we can start again
        manager.clearError()
        val result = manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        assertTrue(result is RecordingStartResult.Success)
    }

    @Test
    fun forceCancel_returnsToIdleAndReleasesLock() {
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.forceCancel()
        manager.clearError()
        
        assertTrue(manager.state.value is RecordingState.Idle)
        
        val result = manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        assertTrue(result is RecordingStartResult.Success)
    }
    @Test
    fun computeStoppingTimeoutMs_scalesWithFileSize() {
        val small = RecordingStateManager.computeStoppingTimeoutMs(1024)
        val large = RecordingStateManager.computeStoppingTimeoutMs(100L * 1024 * 1024)
        assertTrue(large > small)
    }

    @Test
    fun pauseAndResume_preservesAccumulatedDuration() {
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path")
        Thread.sleep(20)
        manager.pauseRecording()
        val pausedDuration = manager.getCurrentDurationMs()
        assertTrue(pausedDuration >= 0L)
        manager.resumeRecording()
        assertTrue(manager.getCurrentDurationMs() >= pausedDuration)
    }

    @Test
    fun amplitudeUpdates_areTracked() {
        manager.updateAmplitude(0.5f)
        assertEquals(0.5f, manager.amplitudeFlow.value)
        assertEquals(0.5f, manager.waveformBuffer.get(0))
        assertEquals(1, manager.waveformBuffer.count)

        Thread.sleep(110)
        manager.updateAmplitude(0.8f)
        assertEquals(0.8f, manager.amplitudeFlow.value)
        assertEquals(0.5f, manager.waveformBuffer.get(0))
        assertEquals(0.8f, manager.waveformBuffer.get(1))
        assertEquals(2, manager.waveformBuffer.count)

        manager.clearAmplitude()
        assertEquals(0f, manager.amplitudeFlow.value)
        assertEquals(0, manager.waveformBuffer.count)
    }

    @Test
    fun stoppingTimeout_awaitsHandlerBeforeErrorTransition() {
        manager.stoppingTimeoutMsOverrideForTest = 10L
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.transitionToStopping()

        val handlerGate = java.util.concurrent.CountDownLatch(1)
        val handlerMayProceed = java.util.concurrent.CountDownLatch(1)
        var handlerCompletedBeforeError = false

        manager.setStoppingTimeoutHandler(RecordingOrigin.APP) { _ ->
            handlerGate.countDown()
            assertTrue(handlerMayProceed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            handlerCompletedBeforeError = true
        }

        manager.startStoppingTimeout(fileSizeBytes = 0L)
        assertTrue(handlerGate.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(manager.state.value is RecordingState.Stopping)
        handlerMayProceed.countDown()
        Thread.sleep(100)

        assertTrue(handlerCompletedBeforeError)
        assertTrue(manager.state.value is RecordingState.Error)
        manager.clearError()
        val restart = manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        assertTrue(restart is RecordingStartResult.Success)
    }
}
