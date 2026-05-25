package dev.chirpboard.app.core.ui.animation

import androidx.compose.animation.core.Spring
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationConstantsTest {

    @Test
    fun `tween helpers wire duration tiers to animation specs`() {
        assertEquals(
            AnimationConstants.DURATION_QUICK_MS,
            AnimationConstants.tweenQuick<Float>().durationMillis,
        )
        assertEquals(
            AnimationConstants.DURATION_STANDARD_MS,
            AnimationConstants.tweenStandard<Float>().durationMillis,
        )
        assertEquals(
            AnimationConstants.DURATION_EMPHASIS_MS,
            AnimationConstants.tweenEmphasis<Float>().durationMillis,
        )
        assertEquals(
            AnimationConstants.DURATION_GLOW_MS,
            AnimationConstants.tweenGlow<Float>().durationMillis,
        )
    }

    @Test
    fun `spring helpers use distinct damping and stiffness profiles`() {
        val bouncy = AnimationConstants.springBouncy<Float>()
        val snappy = AnimationConstants.springSnappy<Float>()
        val gentle = AnimationConstants.springGentle<Float>()

        assertEquals(Spring.DampingRatioMediumBouncy, bouncy.dampingRatio, 0.001f)
        assertEquals(Spring.StiffnessMedium, bouncy.stiffness, 0.001f)

        assertEquals(Spring.DampingRatioNoBouncy, snappy.dampingRatio, 0.001f)
        assertEquals(Spring.StiffnessHigh, snappy.stiffness, 0.001f)

        assertEquals(Spring.DampingRatioLowBouncy, gentle.dampingRatio, 0.001f)
        assertEquals(Spring.StiffnessLow, gentle.stiffness, 0.001f)
    }
}
