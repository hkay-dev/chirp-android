package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RestartStopCoordinatorTest {

    private lateinit var gate: StopRequestGate
    private lateinit var coordinator: RestartStopCoordinator

    @Before
    fun setup() {
        gate = StopRequestGate()
        coordinator = RestartStopCoordinator(gate)
    }

    @Test
    fun `restart is refused while a stop holds the gate and never releases the stop claim`() {
        assertTrue(gate.tryBegin()) // an in-flight stop owns the gate

        assertFalse(coordinator.tryBeginRestart())

        // The refusal must not clobber the stop's claim.
        assertTrue(gate.isInProgress())
        // And the refused restart must not make later rejected stops look restart-queued.
        assertEquals(
            RestartStopCoordinator.RejectedStop.DUPLICATE_STOP,
            coordinator.classifyRejectedStop(),
        )
    }

    @Test
    fun `restart claims the gate and finishRestart releases only its own claim`() {
        assertTrue(coordinator.tryBeginRestart())
        assertTrue(gate.isInProgress())

        coordinator.finishRestart()

        assertFalse(gate.isInProgress())
        assertTrue(gate.tryBegin()) // a stop can claim again afterwards
    }

    @Test
    fun `stop rejected while restart holds the gate is queued and honored exactly once`() {
        assertTrue(coordinator.tryBeginRestart())

        assertEquals(
            RestartStopCoordinator.RejectedStop.QUEUED_BEHIND_RESTART,
            coordinator.classifyRejectedStop(),
        )

        assertTrue(coordinator.consumeStopRequestedDuringRestart())
        assertFalse(coordinator.consumeStopRequestedDuringRestart())
    }

    @Test
    fun `stop rejected by an in-flight stop is classified as duplicate`() {
        assertTrue(gate.tryBegin())

        assertEquals(
            RestartStopCoordinator.RejectedStop.DUPLICATE_STOP,
            coordinator.classifyRejectedStop(),
        )
        assertFalse(coordinator.consumeStopRequestedDuringRestart())
    }

    @Test
    fun `new restart claim clears a stale unconsumed pending stop`() {
        assertTrue(coordinator.tryBeginRestart())
        assertEquals(
            RestartStopCoordinator.RejectedStop.QUEUED_BEHIND_RESTART,
            coordinator.classifyRejectedStop(),
        )
        coordinator.finishRestart()

        assertTrue(coordinator.tryBeginRestart())

        assertFalse(coordinator.consumeStopRequestedDuringRestart())
    }
}
