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
        // TST-012: a controlled clock replaces the former Thread.sleep(20) so the
        // accumulated duration across the rotation is exact instead of wall-clock dependent.
        var now = 1_000L
        manager.nowMsOverrideForTest = { now }
        manager.tryStartRecording(RecordingOrigin.APP)
        manager.onRecordingStarted("/tmp/seg-000.m4a")
        now = 1_020L
        manager.rotateSegment("/tmp/seg-001.m4a")

        val durationAfterRotation = manager.getCurrentDurationMs()
        assertEquals("/tmp/seg-001.m4a", (manager.state.value as RecordingState.Recording).audioFilePath)
        assert(durationAfterRotation >= 15L)
        assertEquals(20L, durationAfterRotation)
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
