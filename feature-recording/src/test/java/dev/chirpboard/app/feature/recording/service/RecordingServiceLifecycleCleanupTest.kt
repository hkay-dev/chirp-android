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
    fun cancelledStart_isPreservedWhenServiceDestroyOwnsEmergencyFinalize() {
        assertTrue(
            RecordingServiceLifecycleCleanup.shouldPreserveCancelledStartForEmergencyStop(
                destroyed = true,
                startGenerationMatches = true,
            ),
        )
    }

    @Test
    fun cancelledStart_isCleanedNormallyOutsideServiceDestroy() {
        assertFalse(
            RecordingServiceLifecycleCleanup.shouldPreserveCancelledStartForEmergencyStop(
                destroyed = false,
                startGenerationMatches = true,
            ),
        )
        assertFalse(
            RecordingServiceLifecycleCleanup.shouldPreserveCancelledStartForEmergencyStop(
                destroyed = true,
                startGenerationMatches = false,
            ),
        )
    }

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
                serviceOwnsCapture = true,
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
                serviceOwnsCapture = true,
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
                serviceOwnsCapture = true,
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
                serviceOwnsCapture = true,
            ),
        )
    }

    @Test
    fun shouldScheduleEmergencyStop_neverForACaptureThisServiceDoesNotOwn() {
        // The shared state can be active because of an in-process keyboard capture; a cold
        // service instance must not emergency-finalize it (the null-recording-id handoff
        // would force the live capture's state to Idle).
        assertFalse(
            RecordingServiceLifecycleCleanup.shouldScheduleEmergencyStop(
                state = RecordingState.Recording(
                    origin = RecordingOrigin.KEYBOARD,
                    profileId = null,
                    audioFilePath = "/tmp/keyboard.m4a",
                ),
                stopInProgress = false,
                serviceOwnsCapture = false,
            ),
        )
    }
}
