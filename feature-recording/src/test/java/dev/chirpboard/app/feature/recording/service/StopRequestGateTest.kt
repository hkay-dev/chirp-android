package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopRequestGateTest {

    @Test
    fun `tryBegin allows only first concurrent stop request`() {
        val gate = StopRequestGate()

        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        assertTrue(gate.isInProgress())
    }

    @Test
    fun `reset allows new stop request`() {
        val gate = StopRequestGate()

        assertTrue(gate.tryBegin())
        gate.reset()
        assertFalse(gate.isInProgress())
        assertTrue(gate.tryBegin())
    }
}
