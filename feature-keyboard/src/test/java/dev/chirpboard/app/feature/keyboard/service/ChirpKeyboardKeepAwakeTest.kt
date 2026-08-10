package dev.chirpboard.app.feature.keyboard.service

import android.view.Window
import android.view.WindowManager
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChirpKeyboardKeepAwakeTest {
    @Test
    fun screenStaysAwake_onlyWhileKeyboardOwnsTheDictation() {
        assertTrue(keyboardKeepsScreenAwake(RecordingState.Recording(RecordingOrigin.KEYBOARD)))
        assertTrue(keyboardKeepsScreenAwake(RecordingState.Stopping(RecordingOrigin.KEYBOARD)))
        // A merely visible keyboard (no session) must not hold the flag.
        assertFalse(keyboardKeepsScreenAwake(RecordingState.Idle))
        // Another surface's recording must not keep the screen on through the IME window.
        assertFalse(keyboardKeepsScreenAwake(RecordingState.Recording(RecordingOrigin.APP)))
    }

    @Test
    fun visibleWindow_keepsTheScreenOn() {
        val window = mockk<Window>(relaxed = true)

        updateImeKeepScreenOn(window, enabled = true)

        verify(exactly = 1) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        verify(exactly = 0) {
            window.clearFlags(any())
        }
    }

    @Test
    fun hiddenWindow_releasesTheScreenFlag() {
        val window = mockk<Window>(relaxed = true)

        updateImeKeepScreenOn(window, enabled = false)

        verify(exactly = 1) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        verify(exactly = 0) {
            window.addFlags(any())
        }
    }
}
