package dev.chirpboard.app.core.recording

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import org.junit.Test

class RecordingStateManagerSegmentTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var manager: RecordingStateManager

    @Before
    fun setup() {
        manager = RecordingStateManager()
    }

    @Test
    fun rotateSegment_preservesAccumulatedDuration() {
        manager.tryStartRecording(RecordingOrigin.APP)
        manager.onRecordingStarted("/tmp/seg-000.m4a")
        Thread.sleep(20)
        manager.rotateSegment("/tmp/seg-001.m4a")

        val durationAfterRotation = manager.getCurrentDurationMs()
        assertEquals("/tmp/seg-001.m4a", (manager.state.value as RecordingState.Recording).audioFilePath)
        assert(durationAfterRotation >= 15L)
    }

    @Test
    fun resumeRecording_startsFreshHiddenSegment() {
        manager.tryStartRecording(RecordingOrigin.APP)
        manager.onRecordingStarted("/tmp/seg-000.m4a")
        manager.pauseRecording()
        manager.resumeRecording("/tmp/seg-001.m4a")

        val state = manager.state.value as RecordingState.Recording
        assertEquals("/tmp/seg-001.m4a", state.audioFilePath)
    }
}
