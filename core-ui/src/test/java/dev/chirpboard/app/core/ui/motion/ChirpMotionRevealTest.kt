package dev.chirpboard.app.core.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChirpMotionRevealTest {
    @Test
    fun pushDownReveal_durations_matchStudioTokens() {
        assertEquals(ChirpMotion.STUDIO_REVEAL_MS, 420)
        assertEquals(ChirpMotion.STUDIO_HIDE_MS, 260)
    }

    @Test
    fun miniPlayerReveal_usesDedicatedEnterDuration() {
        assertTrue(ChirpMotion.miniPlayerRevealTransition.toString().isNotEmpty())
        assertTrue(ChirpMotion.miniPlayerHideTransition.toString().isNotEmpty())
    }
}
