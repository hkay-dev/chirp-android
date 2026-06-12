package dev.chirpboard.app.feature.recording.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MIC-019: a pause/resume failure used to flip the state machine to Error and stop there,
 * leaking the half-finalized engine, the audio focus request (other apps stayed ducked)
 * and the foreground notification until the next start. The cleanup sequence must release
 * everything — and must never convert a recoverable multi-segment session into an
 * abandoned one.
 */
class RecordingSessionFailureCleanupTest {
    private suspend fun runCleanup(
        recoverable: Boolean,
        events: MutableList<String>,
    ) {
        RecordingSessionFailureCleanup.run(
            releaseEngineNonDestructively = { events += "release-engine" },
            abandonFocus = { events += "abandon-focus" },
            hasRecoverableArtifacts = {
                events += "recoverability-check"
                recoverable
            },
            abandonSessionArtifacts = { events += "abandon-artifacts" },
            onRecordingError = { events += "recording-error" },
            stopService = { events += "stop-service" },
        )
    }

    @Test
    fun `recoverable session keeps its journal entry for the startup reconciler`() =
        runTest {
            val events = mutableListOf<String>()

            runCleanup(recoverable = true, events = events)

            // No "abandon-artifacts": a multi-segment session must be recovered at the
            // next launch, never discarded by an error-path cleanup.
            assertEquals(
                listOf(
                    "release-engine",
                    "abandon-focus",
                    "recoverability-check",
                    "recording-error",
                    "stop-service",
                ),
                events,
            )
        }

    @Test
    fun `unrecoverable session is abandoned before the error is reported`() =
        runTest {
            val events = mutableListOf<String>()

            runCleanup(recoverable = false, events = events)

            assertEquals(
                listOf(
                    "release-engine",
                    "abandon-focus",
                    "recoverability-check",
                    "abandon-artifacts",
                    "recording-error",
                    "stop-service",
                ),
                events,
            )
        }
}
