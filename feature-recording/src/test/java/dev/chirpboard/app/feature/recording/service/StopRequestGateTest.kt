package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StopRequestGateTest {

    private lateinit var gate: StopRequestGate

    @Before
    fun setup() {
        gate = StopRequestGate()
    }

    @Test
    fun `tryBegin returns true initially and sets inProgress`() {
        assertFalse(gate.isInProgress())
        assertTrue(gate.tryBegin())
        assertTrue(gate.isInProgress())
    }

    @Test
    fun `tryBegin returns false if already in progress`() {
        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        assertTrue(gate.isInProgress())
    }

    @Test
    fun `reset clears inProgress state allowing tryBegin again`() {
        assertTrue(gate.tryBegin())
        assertTrue(gate.isInProgress())

        gate.reset()

        assertFalse(gate.isInProgress())
        assertTrue(gate.tryBegin())
    }
}
