package dev.chirpboard.app.feature.recording.service

import org.junit.Test

class RecordingServiceTest {
    @Test
    fun `stop request gate rejects duplicate stop while active`() {
        val gate = StopRequestGate()
        org.junit.Assert.assertTrue(gate.tryBegin())
        org.junit.Assert.assertFalse(gate.tryBegin())
    }
}
