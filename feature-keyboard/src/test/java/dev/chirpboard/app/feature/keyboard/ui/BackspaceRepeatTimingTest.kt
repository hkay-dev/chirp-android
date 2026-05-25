package dev.chirpboard.app.feature.keyboard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackspaceRepeatTimingTest {
    @Test
    fun `word mode activates after threshold`() {
        assertFalse(shouldEnterBackspaceWordMode(BackspaceWordModeHoldMs - 1))
        assertTrue(shouldEnterBackspaceWordMode(BackspaceWordModeHoldMs))
    }

    @Test
    fun `character repeat interval accelerates with hold duration`() {
        val early = backspaceRepeatIntervalMs(BackspaceInitialRepeatDelayMs + 100, wordMode = false)
        val late = backspaceRepeatIntervalMs(BackspaceInitialRepeatDelayMs + 2_000, wordMode = false)

        assertTrue(early > late)
        assertTrue(late >= 35L)
    }

    @Test
    fun `word repeat interval stays in fast range`() {
        val start = backspaceRepeatIntervalMs(BackspaceWordModeHoldMs, wordMode = true)
        val ramped = backspaceRepeatIntervalMs(BackspaceWordModeHoldMs + 2_000, wordMode = true)

        assertTrue(start in 70L..110L)
        assertTrue(ramped in 70L..110L)
        assertTrue(start >= ramped)
    }
}
