package dev.chirpboard.app.feature.keyboard.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPointerBoundsTest {
    @Test
    fun `point inside key bounds is accepted`() {
        assertTrue(isPointerInsideKey(position = Offset(24f, 24f), width = 48, height = 48))
    }

    @Test
    fun `point outside key bounds is rejected`() {
        assertFalse(isPointerInsideKey(position = Offset(49f, 24f), width = 48, height = 48))
        assertFalse(isPointerInsideKey(position = Offset(24f, -1f), width = 48, height = 48))
    }
}
