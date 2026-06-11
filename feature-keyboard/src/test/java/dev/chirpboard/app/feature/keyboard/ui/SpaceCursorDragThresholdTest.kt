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
    fun `movement beyond threshold starts cursor drag`() {
        assertTrue(shouldStartSpaceCursorDrag(dx = 6f, dy = 8f, thresholdPx = 6f))
    }
}
