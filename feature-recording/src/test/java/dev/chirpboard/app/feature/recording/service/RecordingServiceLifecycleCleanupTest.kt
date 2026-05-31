package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingServiceLifecycleCleanupTest {
    @Test
    fun prepareDestroy_cancelsJobsAndDetachesCallbacksBeforeSchedulingEmergencyStop() {
        val events = mutableListOf<String>()

        val plan =
            RecordingServiceLifecycleCleanup.prepareDestroy(
                state = RecordingState.Recording(
                    origin = RecordingOrigin.APP,
                    profileId = null,
                    audioFilePath = "/tmp/active.m4a",
                    recordingId = UUID.randomUUID(),
                ),
                stopInProgress = false,
                cancelPeriodicJobs = { events += "cancel-jobs" },
                detachCallbacks = { events += "detach-callbacks" },
            )

        assertEquals(listOf("cancel-jobs", "detach-callbacks"), events)
        assertTrue(plan.scheduleEmergencyStop)
    }

    @Test
    fun shouldScheduleEmergencyStop_onlyForActiveCaptureWithoutStopInProgress() {
        assertTrue(
            RecordingServiceLifecycleCleanup.shouldScheduleEmergencyStop(
                state = RecordingState.Paused(
                    origin = RecordingOrigin.APP,
                    profileId = null,
                    audioFilePath = "/tmp/active.m4a",
                    accumulatedMs = 1_000L,
                ),
                stopInProgress = false,
            ),
        )
        assertFalse(
            RecordingServiceLifecycleCleanup.shouldScheduleEmergencyStop(
                state = RecordingState.Stopping(
                    origin = RecordingOrigin.APP,
                    profileId = null,
                    audioFilePath = "/tmp/active.m4a",
                ),
                stopInProgress = false,
            ),
        )
        assertFalse(
            RecordingServiceLifecycleCleanup.shouldScheduleEmergencyStop(
                state = RecordingState.Recording(
                    origin = RecordingOrigin.APP,
                    profileId = null,
                    audioFilePath = "/tmp/active.m4a",
                ),
                stopInProgress = true,
            ),
        )
    }
}
