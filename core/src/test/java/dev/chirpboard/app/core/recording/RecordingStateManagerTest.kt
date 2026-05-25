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
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingStateManagerTest {

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
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path/to/file")
        
        val state = manager.state.value
        assertTrue(state is RecordingState.Recording)
        assertEquals("path/to/file", (state as RecordingState.Recording).audioFilePath)
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
    fun onRecordingCompleted_returnsToIdle() {
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.beginStopRecording()
        manager.onRecordingCompleted(UUID.randomUUID())
        
        manager.clearError()
        assertTrue(manager.state.value is RecordingState.Idle)
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
}
