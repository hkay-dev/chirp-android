package dev.chirpboard.app.feature.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * INS-1: the keyboard must reserve a bottom inset for Samsung's IME-nav strip even when Good Lock
 * zeroes the gesture/navigation insets, so the system IME-switcher + collapse buttons cannot
 * overlap backspace/Space.
 */
class KeyboardBottomInsetTest {
    @Test
    fun `floors to the min strip when system insets are zeroed by Good Lock`() {
        // Good Lock hides the gesture hint, so navigationBars + systemGestures both report 0.
        val px = resolveKeyboardBottomInsetPx(navBarsBottomPx = 0, systemGesturesBottomPx = 0, minStripPx = 90)
        assertEquals(90, px)
    }

    @Test
    fun `uses the larger navigation bar inset when a real nav bar is present`() {
        // A 3-button nav bar reports more than the min strip; take the real inset.
        val px = resolveKeyboardBottomInsetPx(navBarsBottomPx = 132, systemGesturesBottomPx = 0, minStripPx = 90)
        assertEquals(132, px)
    }

    @Test
    fun `uses the larger gesture inset when it exceeds the nav bar inset and floor`() {
        val px = resolveKeyboardBottomInsetPx(navBarsBottomPx = 40, systemGesturesBottomPx = 110, minStripPx = 90)
        assertEquals(110, px)
    }

    @Test
    fun `never returns less than the floor`() {
        val px = resolveKeyboardBottomInsetPx(navBarsBottomPx = 12, systemGesturesBottomPx = 8, minStripPx = 90)
        assertEquals(90, px)
    }
}
