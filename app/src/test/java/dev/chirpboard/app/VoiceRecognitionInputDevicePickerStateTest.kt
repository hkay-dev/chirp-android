package dev.chirpboard.app

import dev.chirpboard.app.core.audio.ActiveInputDevice
import dev.chirpboard.app.core.audio.AudioInputDeviceKind
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.InputDevicePickerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the recognition dialog's input-device picker state derivation:
 *
 *  - MIC-004: [recognitionSessionLive] mirrors the dialog's Starting/Recording/Stopping
 *    liveness, and a live session's chip shows the session's ACTUAL device even when it
 *    diverges from the predicted-next selection;
 *  - MIC-014: [withDeviceLostNotice] overlays a mid-session device loss onto the active
 *    device's fallback annotation so the existing "Using X — Y isn't connected" notice
 *    explains the silent platform reroute.
 */
class VoiceRecognitionInputDevicePickerStateTest {
    private val builtIn =
        AudioInputDeviceSummary(
            id = 1,
            productName = "Built-in microphone",
            typeLabel = "Built-in",
            kind = AudioInputDeviceKind.BuiltIn,
            address = null,
            selectionKey = "device:builtin:Built-in microphone",
        )

    private val buds =
        AudioInputDeviceSummary(
            id = 7,
            productName = "Buds",
            typeLabel = "Bluetooth",
            kind = AudioInputDeviceKind.Bluetooth,
            address = "AA:BB:CC:DD:EE:FF",
            selectionKey = "AA:BB:CC:DD:EE:FF",
        )

    @Test
    fun `session is live while starting recording and stopping`() {
        assertTrue(recognitionSessionLive(RecordingState.Starting(RecordingOrigin.RECOGNITION)))
        assertTrue(
            recognitionSessionLive(RecordingState.Recording(RecordingOrigin.RECOGNITION, startTimeMs = 0L)),
        )
        assertTrue(recognitionSessionLive(RecordingState.Stopping(RecordingOrigin.RECOGNITION)))
    }

    @Test
    fun `session is not live while idle or errored`() {
        assertFalse(recognitionSessionLive(RecordingState.Idle))
        assertFalse(recognitionSessionLive(RecordingState.Error(RecordingOrigin.RECOGNITION, message = "boom")))
    }

    @Test
    fun `live session chip shows the actual device even when it diverges from the selection`() {
        // The manual preference points at the buds, but the LIVE session is capturing from
        // the built-in mic (preference changed mid-session, or fallback at start): while
        // live the chip must show the session's actual device, not the predicted-next one.
        val state =
            InputDevicePickerUiState(
                devices = listOf(builtIn, buds),
                policy = AudioInputDevicePolicy.Manual,
                manualKey = buds.selectionKey,
                manualName = buds.productName,
                activeDevice = ActiveInputDevice(summary = builtIn),
                sessionLive = true,
            )
        assertEquals(builtIn, state.chipDevice())

        // Once the session ends, the chip flips to the device the NEXT capture will use.
        assertEquals(buds, state.copy(sessionLive = false, activeDevice = null).chipDevice())
    }

    @Test
    fun `device lost overlay annotates the active device for the fallback notice`() {
        val active = ActiveInputDevice(summary = builtIn)
        val overlaid = active.withDeviceLostNotice("Buds")
        assertEquals("Buds", overlaid?.fallbackFromPreferredName)
        assertEquals(builtIn, overlaid?.summary)
    }

    @Test
    fun `device lost overlay is a no-op without a live device or a usable name`() {
        val active = ActiveInputDevice(summary = builtIn)
        assertNull((null as ActiveInputDevice?).withDeviceLostNotice("Buds"))
        assertEquals(active, active.withDeviceLostNotice(null))
        assertEquals(active, active.withDeviceLostNotice("  "))
    }
}
