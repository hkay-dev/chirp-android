package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingResumeGuardTest {
    private fun paused(): RecordingState =
        RecordingState.Paused(
            origin = RecordingOrigin.APP,
            profileId = null,
            audioFilePath = "/tmp/active.m4a",
            accumulatedMs = 1_000L,
        )

    @Test
    fun canResume_allowsPausedSessionWithNoStopInFlight() {
        assertTrue(RecordingResumeGuard.canResume(state = paused(), stopInProgress = false))
    }

    @Test
    fun canResume_refusesWhileGatedStopOwnsTheSession() {
        // AUD-05 race: a focus-paused session stays Paused until the stop's capture handoff
        // lands, so the Paused state alone must never be enough to start a new engine.
        assertFalse(RecordingResumeGuard.canResume(state = paused(), stopInProgress = true))
    }

    @Test
    fun canResume_refusesEveryNonPausedState() {
        val nonPaused =
            listOf(
                RecordingState.Idle,
                RecordingState.Starting(origin = RecordingOrigin.APP),
                RecordingState.Recording(origin = RecordingOrigin.APP),
                RecordingState.Stopping(origin = RecordingOrigin.APP),
            )

        nonPaused.forEach { state ->
            assertFalse(
                "resume must be refused for $state",
                RecordingResumeGuard.canResume(state = state, stopInProgress = false),
            )
        }
    }
}
