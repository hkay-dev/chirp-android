package dev.chirpboard.app.core.recording

import dev.chirpboard.app.core.testing.MockAndroidLogRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Duration arithmetic must ride a monotonic clock: an NTP/RTC correction or a manual clock
 * change mid-recording moves the wall clock, and a backwards jump used to clamp the whole
 * session's stored duration to zero.
 */
class RecordingStateManagerMonotonicClockTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var manager: RecordingStateManager

    @Before
    fun setup() {
        manager = RecordingStateManager()
    }

    @Test
    fun recordingState_carriesAMonotonicSegmentBaseSeparateFromTheWallClockStamp() {
        var monotonic = 5_000L
        manager.nowMsOverrideForTest = { monotonic }
        val beforeStart = System.currentTimeMillis()
        manager.tryStartRecording(RecordingOrigin.APP)
        manager.onRecordingStarted("/tmp/seg-000.m4a")

        val state = manager.state.value as RecordingState.Recording
        assertEquals(5_000L, state.startMonotonicMs)
        assertTrue(
            "startTimeMs must stay a real calendar timestamp",
            state.startTimeMs >= beforeStart,
        )
    }

    @Test
    fun durationIgnoresBackwardsWallClockJumpDuringRecording() {
        var monotonic = 1_000L
        manager.nowMsOverrideForTest = { monotonic }
        manager.tryStartRecording(RecordingOrigin.APP)
        manager.onRecordingStarted("/tmp/seg-000.m4a")

        // The wall clock is corrected an hour backwards; the monotonic clock keeps ticking.
        val skewed =
            (manager.state.value as RecordingState.Recording)
                .copy(startTimeMs = System.currentTimeMillis() + 3_600_000L)
        assertEquals(1_000L, skewed.startMonotonicMs)

        monotonic = 31_000L
        assertEquals(30_000L, manager.getCurrentDurationMs())
    }

    @Test
    fun pauseResumeAndRotationAccumulateAcrossAWallClockCorrection() {
        var monotonic = 0L
        manager.nowMsOverrideForTest = { monotonic }
        manager.tryStartRecording(RecordingOrigin.APP)
        manager.onRecordingStarted("/tmp/seg-000.m4a")

        monotonic = 10_000L
        manager.rotateSegment("/tmp/seg-001.m4a")
        assertEquals(10_000L, manager.getCurrentDurationMs())

        monotonic = 15_000L
        manager.pauseRecording()
        assertEquals(15_000L, manager.getCurrentDurationMs())

        // Paused wall time is not recorded time, and neither is a clock change during it.
        monotonic = 60_000L
        manager.resumeRecording("/tmp/seg-002.m4a")
        assertEquals(15_000L, manager.getCurrentDurationMs())

        monotonic = 65_000L
        assertEquals(20_000L, manager.getCurrentDurationMs())
    }
}
