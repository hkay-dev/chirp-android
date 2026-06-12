package dev.chirpboard.app.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimmerTest {

    private val width = 200f
    private val band = 90f

    @Test
    fun shimmerTranslate_startsFullyOffLeftEdge() {
        // At progress 0 the band's left edge is one band-width to the left of the surface, so the
        // highlight is entirely off-screen and the sweep begins cleanly.
        assertEquals(-band, shimmerTranslate(0f, width, band), 0.001f)
    }

    @Test
    fun shimmerTranslate_endsFullyOffRightEdge() {
        // At progress 1 the band's left edge is at width + band, so the whole band sits past the
        // right edge — the sweep finishes cleanly before restarting.
        assertEquals(width + band, shimmerTranslate(1f, width, band), 0.001f)
    }

    @Test
    fun shimmerTranslate_isMonotonicAcrossSweep() {
        var previous = shimmerTranslate(0f, width, band)
        listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f).forEach { p ->
            val current = shimmerTranslate(p, width, band)
            assertTrue("translate must increase with progress", current > previous)
            previous = current
        }
    }

    @Test
    fun shimmerTranslate_clampsOutOfRangeProgress() {
        assertEquals(shimmerTranslate(0f, width, band), shimmerTranslate(-0.5f, width, band), 0.001f)
        assertEquals(shimmerTranslate(1f, width, band), shimmerTranslate(1.5f, width, band), 0.001f)
    }
}
