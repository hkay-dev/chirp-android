package dev.chirpboard.app.feature.keyboard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCursorDragThresholdTest {
    @Test
    fun `movement inside threshold remains a space tap`() {
        assertFalse(shouldStartSpaceCursorDrag(dx = 3f, dy = 4f, thresholdPx = 6f))
    }

    @Test
    fun `horizontal movement beyond threshold starts cursor drag`() {
        assertTrue(shouldStartSpaceCursorDrag(dx = 8f, dy = 2f, thresholdPx = 6f))
    }

    @Test
    fun `vertical movement beyond threshold remains a space tap`() {
        assertFalse(shouldStartSpaceCursorDrag(dx = 2f, dy = 8f, thresholdPx = 6f))
    }
}
