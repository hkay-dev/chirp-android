package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIC-009: losing the ACTIVE input device must auto-stop the session only while capture is
 * genuinely live. Paused is benign — pause already released the mic, so unplugging the
 * device then is the supported pause -> swap -> resume flow (resume re-resolves the device
 * and annotates any fallback); force-stopping there destroyed that flow.
 */
class RecordingDeviceLossPolicyTest {
    @Test
    fun `device loss while recording stops with save`() {
        // Regression pin: the deliberate no-silent-mid-recording-device-swap auto-stop.
        assertTrue(
            RecordingDeviceLossPolicy.shouldAutoStop(
                state = RecordingState.Recording(origin = RecordingOrigin.APP),
                ownsCapture = true,
            ),
        )
    }

    @Test
    fun `device loss while starting still aborts deliberately`() {
        // A device that disappears mid-start should abort with a named reason rather than
        // silently record from a surprise fallback.
        assertTrue(
            RecordingDeviceLossPolicy.shouldAutoStop(
                state = RecordingState.Starting(origin = RecordingOrigin.APP),
                ownsCapture = true,
            ),
        )
    }

    @Test
    fun `device loss while paused is benign`() {
        // The mic is already closed; nothing was being captured from the lost device.
        assertFalse(
            RecordingDeviceLossPolicy.shouldAutoStop(
                state = RecordingState.Paused(origin = RecordingOrigin.APP),
                ownsCapture = true,
            ),
        )
    }

    @Test
    fun `device loss while stopping idle or errored is ignored`() {
        val inertStates =
            listOf(
                RecordingState.Stopping(origin = RecordingOrigin.APP),
                RecordingState.Idle,
                RecordingState.Error(origin = RecordingOrigin.APP, message = "boom"),
            )

        inertStates.forEach { state ->
            assertFalse(
                "device loss must be ignored for $state",
                RecordingDeviceLossPolicy.shouldAutoStop(state = state, ownsCapture = true),
            )
        }
    }

    @Test
    fun `unowned capture never auto-stops regardless of state`() {
        // In-process keyboard/recognition captures detect device death through their own
        // AudioRecord read errors; a cold service instance must never stop them.
        assertFalse(
            RecordingDeviceLossPolicy.shouldAutoStop(
                state = RecordingState.Recording(origin = RecordingOrigin.KEYBOARD),
                ownsCapture = false,
            ),
        )
    }
}
