package dev.chirpboard.app.core.ui.animation

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AnimationConstantsTest {

    @Test
    fun `test standard duration constants are correct`() {
        assertEquals(200, AnimationConstants.DURATION_QUICK_MS)
        assertEquals(300, AnimationConstants.DURATION_STANDARD_MS)
        assertEquals(400, AnimationConstants.DURATION_EMPHASIS_MS)
        assertEquals(500, AnimationConstants.DURATION_GLOW_MS)
    }

    @Test
    fun `test timer update intervals`() {
        assertEquals(500L, AnimationConstants.TIMER_UPDATE_INTERVAL_MS)
        assertEquals(16L, AnimationConstants.AMPLITUDE_DEBOUNCE_MS)
        assertEquals(100, AnimationConstants.SLIDER_ANIMATION_MS)
    }

    @Test
    fun `test scale values`() {
        assertEquals(0.98f, AnimationConstants.PRESS_SCALE, 0.001f)
        assertEquals(0.85f, AnimationConstants.DIALOG_ENTRY_SCALE, 0.001f)
    }

    @Test
    fun `test tween generation functions return expected specs`() {
        val quickTween = AnimationConstants.tweenQuick<Float>()
        assertNotNull(quickTween)

        val standardTween = AnimationConstants.tweenStandard<Float>()
        assertNotNull(standardTween)

        val emphasisTween = AnimationConstants.tweenEmphasis<Float>()
        assertNotNull(emphasisTween)

        val glowTween = AnimationConstants.tweenGlow<Float>()
        assertNotNull(glowTween)
    }

    @Test
    fun `test spring generation functions return expected specs`() {
        val bouncySpring = AnimationConstants.springBouncy<Float>()
        assertNotNull(bouncySpring)

        val snappySpring = AnimationConstants.springSnappy<Float>()
        assertNotNull(snappySpring)

        val gentleSpring = AnimationConstants.springGentle<Float>()
        assertNotNull(gentleSpring)
    }
}
