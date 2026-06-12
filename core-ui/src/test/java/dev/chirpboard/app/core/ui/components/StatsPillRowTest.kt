package dev.chirpboard.app.core.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsPillRowTest {

    @Test
    fun processingPill_highlighted_whenItemsAreProcessing() {
        assertTrue(processingPillHighlighted(processingCount = 1, processingFilterActive = false))
        assertTrue(processingPillHighlighted(processingCount = 5, processingFilterActive = false))
    }

    @Test
    fun processingPill_highlighted_whenFilterActive() {
        assertTrue(processingPillHighlighted(processingCount = 0, processingFilterActive = true))
    }

    @Test
    fun processingPill_notHighlighted_whenIdleAndUnfiltered() {
        assertFalse(processingPillHighlighted(processingCount = 0, processingFilterActive = false))
    }
}
