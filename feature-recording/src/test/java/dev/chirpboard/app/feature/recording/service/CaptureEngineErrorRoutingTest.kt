package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureEngineErrorRoutingTest {

    @Test
    fun `destroyed service drops the error without claiming the stop gate`() {
        var gateClaimed = false

        val decision =
            CaptureEngineErrorRouting.decide(
                destroyed = true,
                engineIsActive = true,
                claimStopGate = {
                    gateClaimed = true
                    true
                },
            )

        assertEquals(CaptureEngineErrorRouting.Decision.DROP_DESTROYED, decision)
        assertFalse(gateClaimed)
    }

    @Test
    fun `stale engine drops the error without claiming the stop gate`() {
        var gateClaimed = false

        val decision =
            CaptureEngineErrorRouting.decide(
                destroyed = false,
                engineIsActive = false,
                claimStopGate = {
                    gateClaimed = true
                    true
                },
            )

        assertEquals(CaptureEngineErrorRouting.Decision.DROP_STALE_ENGINE, decision)
        assertFalse(gateClaimed)
    }

    @Test
    fun `stop already in flight downgrades the error to informational`() {
        val gate = StopRequestGate()
        assertTrue(gate.tryBegin()) // the in-flight stop owns the gate

        val decision =
            CaptureEngineErrorRouting.decide(
                destroyed = false,
                engineIsActive = true,
                claimStopGate = gate::tryBegin,
            )

        assertEquals(CaptureEngineErrorRouting.Decision.INFORMATIONAL_STOP_IN_FLIGHT, decision)
        // The downgrade must not have released the stop's claim.
        assertTrue(gate.isInProgress())
    }

    @Test
    fun `live error from the active engine claims the gate and stops with save`() {
        val gate = StopRequestGate()

        val decision =
            CaptureEngineErrorRouting.decide(
                destroyed = false,
                engineIsActive = true,
                claimStopGate = gate::tryBegin,
            )

        assertEquals(CaptureEngineErrorRouting.Decision.STOP_WITH_SAVE, decision)
        // The error path now owns the gate, so a duplicate stop is rejected.
        assertFalse(gate.tryBegin())
    }
}
