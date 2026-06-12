package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.core.audio.ActiveInputDevice
import dev.chirpboard.app.core.audio.AudioInputDeviceKind
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.InputDevicePickerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIC-004 (keyboard half): the IME picker's origin-scoped sessionLive derivation and the
 * live-session behavior it unlocks in the shared picker state — with sessionLive set, the
 * chip pins the LIVE session's actual device (and the sheet renders the applies-next-session
 * note) instead of the predicted-next device.
 */
class KeyboardInputDevicePickerStateTest {
    private fun device(
        id: Int,
        name: String,
        kind: AudioInputDeviceKind = AudioInputDeviceKind.BuiltIn,
    ): AudioInputDeviceSummary =
        AudioInputDeviceSummary(
            id = id,
            productName = name,
            typeLabel = "label",
            kind = kind,
            address = null,
            selectionKey = "key-$id",
        )

    @Test
    fun `keyboard-origin recording marks the picker session live across every active state`() {
        assertTrue(keyboardPickerSessionLive(RecordingState.Starting(RecordingOrigin.KEYBOARD)))
        assertTrue(keyboardPickerSessionLive(RecordingState.Recording(RecordingOrigin.KEYBOARD)))
        assertTrue(keyboardPickerSessionLive(RecordingState.Stopping(RecordingOrigin.KEYBOARD)))
    }

    @Test
    fun `other-origin recordings and terminal states never mark the keyboard picker live`() {
        // Origin-scoped: an app/widget/recognition capture must not pin the IME picker to a
        // device its own next session will not use.
        assertFalse(keyboardPickerSessionLive(RecordingState.Idle))
        assertFalse(keyboardPickerSessionLive(RecordingState.Recording(RecordingOrigin.APP)))
        assertFalse(keyboardPickerSessionLive(RecordingState.Recording(RecordingOrigin.WIDGET)))
        assertFalse(keyboardPickerSessionLive(RecordingState.Recording(RecordingOrigin.RECOGNITION)))
        assertFalse(keyboardPickerSessionLive(RecordingState.Error(RecordingOrigin.KEYBOARD, "boom")))
    }

    @Test
    fun `live session pins the chip to the active device even when it diverges from the prediction`() {
        val builtIn = device(1, "Built-in mic")
        val usb = device(2, "USB mic", AudioInputDeviceKind.Usb)
        // The session is actually capturing from USB (it started before the manual change
        // to built-in); the chip must show the live device, not the predicted-next one.
        val state =
            InputDevicePickerUiState(
                devices = listOf(builtIn, usb),
                policy = AudioInputDevicePolicy.Manual,
                manualKey = builtIn.selectionKey,
                activeDevice = ActiveInputDevice(summary = usb),
                sessionLive = true,
            )

        assertEquals(usb, state.chipDevice())
    }

    @Test
    fun `without a live session the chip predicts the next capture's device`() {
        val builtIn = device(1, "Built-in mic")
        val usb = device(2, "USB mic", AudioInputDeviceKind.Usb)
        // A stale activeDevice left over from a finished session must not outrank the
        // manual preference once nothing is live.
        val state =
            InputDevicePickerUiState(
                devices = listOf(builtIn, usb),
                policy = AudioInputDevicePolicy.Manual,
                manualKey = builtIn.selectionKey,
                activeDevice = ActiveInputDevice(summary = usb),
                sessionLive = false,
            )

        assertEquals(builtIn, state.chipDevice())
    }
}
