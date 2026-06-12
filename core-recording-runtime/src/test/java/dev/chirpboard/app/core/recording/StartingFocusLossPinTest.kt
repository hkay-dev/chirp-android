package dev.chirpboard.app.core.recording

import dev.chirpboard.app.core.testing.MockAndroidLogRule
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * MIC-020 item 19 — a transient focus loss during [RecordingState.Starting] must NOT pause.
 *
 * The recording service wires `AudioFocusManager.onFocusLost(TRANSIENT)` to
 * `pauseRecording()`. Pausing is gated entirely on the session being in
 * [RecordingState.Recording] (`RecordingService.pauseRecording` re-checks
 * `state !is Recording` under the segment mutex, and [RecordingStateManager.pauseRecording]
 * is itself a no-op outside [RecordingState.Recording]). So a focus blip arriving in the
 * brief [RecordingState.Starting] window — before capture has actually begun — is silently
 * ignored rather than dropping the not-yet-started session into a stuck Paused state.
 *
 * This pins the intended behavior at the state-machine gate that enforces it: pause requires
 * [RecordingState.Recording]; focus loss in any other state is inert.
 */
class StartingFocusLossPinTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var manager: RecordingStateManager

    @Before
    fun setUp() {
        manager = RecordingStateManager()
    }

    /**
     * Mirrors `RecordingService.onCreate`'s focus wiring: a transient focus loss routes to
     * `pauseRecording()`. The service's own re-check and this manager method share the same
     * "only when Recording" gate, so driving the manager exercises the real guard.
     */
    private fun onTransientFocusLoss() {
        manager.pauseRecording()
    }

    @Test
    fun transientFocusLossWhileStarting_doesNotPause() {
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingIdAssigned(UUID.randomUUID())
        assertTrue(manager.state.value is RecordingState.Starting)

        // A notification chime / assistant blip arrives in the Starting window.
        onTransientFocusLoss()

        // The session is left exactly where it was — never dropped into Paused.
        assertTrue(
            "focus loss during Starting must leave the session Starting, not Paused",
            manager.state.value is RecordingState.Starting,
        )
    }

    @Test
    fun transientFocusLossOnceRecording_doesPause() {
        // The other half of the contract: pausing only ever happens from Recording. Once the
        // session has actually started capturing, the same focus loss DOES pause it (which the
        // service then auto-resumes when focus returns).
        manager.tryStartRecording(origin = RecordingOrigin.APP, profileId = null)
        manager.onRecordingStarted(audioFilePath = "path/to/file")
        assertTrue(manager.state.value is RecordingState.Recording)

        onTransientFocusLoss()

        assertTrue(
            "focus loss while Recording must pause the session",
            manager.state.value is RecordingState.Paused,
        )
    }

    @Test
    fun transientFocusLossWhileIdle_isInert() {
        // No active session at all (focus blip racing teardown / before any start): inert.
        assertTrue(manager.state.value is RecordingState.Idle)

        onTransientFocusLoss()

        assertTrue(manager.state.value is RecordingState.Idle)
    }
}
