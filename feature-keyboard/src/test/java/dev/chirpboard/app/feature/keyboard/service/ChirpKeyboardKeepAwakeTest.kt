package dev.chirpboard.app.feature.keyboard.service

import android.view.Window
import android.view.WindowManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ChirpKeyboardKeepAwakeTest {
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
