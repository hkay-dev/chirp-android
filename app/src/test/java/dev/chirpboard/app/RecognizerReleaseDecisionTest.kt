package dev.chirpboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognizerReleaseDecisionTest {
    @Test
    fun `quiet resident recognizer can release`() {
        assertEquals(
            RecognizerReleaseDecision.RELEASE,
            recognizerReleaseDecision(isResident = true, activeLeases = 0, isExternallyBusy = false),
        )
    }

    @Test
    fun `active decode blocks pressure and switch release`() {
        assertEquals(
            RecognizerReleaseDecision.IN_USE,
            recognizerReleaseDecision(isResident = true, activeLeases = 1, isExternallyBusy = false),
        )
    }

    @Test
    fun `capture or queued work blocks pressure release`() {
        assertEquals(
            RecognizerReleaseDecision.EXTERNALLY_BUSY,
            recognizerReleaseDecision(isResident = true, activeLeases = 0, isExternallyBusy = true),
        )
    }

    @Test
    fun `cold recognizer is a no-op`() {
        assertEquals(
            RecognizerReleaseDecision.NOT_RESIDENT,
            recognizerReleaseDecision(isResident = false, activeLeases = 0, isExternallyBusy = false),
        )
    }
}

