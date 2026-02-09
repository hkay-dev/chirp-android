package dev.chirpboard.app.core.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityEventLoggerTest {

    @Test
    fun `logger stores event and caps message length`() {
        ReliabilityEventLogger.clear()

        val longMessage = "x".repeat(500)
        ReliabilityEventLogger.log(
            stage = ReliabilityStage.TRANSCRIPTION,
            outcome = ReliabilityOutcome.FAILURE,
            correlationId = "corr-123",
            reasonCode = "failure_reason",
            message = longMessage
        )

        val events = ReliabilityEventLogger.events.value
        assertEquals(1, events.size)
        assertEquals(ReliabilityStage.TRANSCRIPTION, events.first().stage)
        assertEquals(ReliabilityOutcome.FAILURE, events.first().outcome)
        assertEquals("corr-123", events.first().correlationId)
        assertTrue((events.first().message ?: "").length <= 200)
    }

    @Test
    fun `redactMessage removes obvious paths`() {
        val message = "failed reading /storage/emulated/0/Download/file.txt"
        val redacted = redactMessage(message)

        assertTrue(redacted?.contains("[path]") == true)
    }

    @Test
    fun `new correlation id includes prefix`() {
        val correlationId = ReliabilityEventLogger.newCorrelationId("queue")
        assertTrue(correlationId.startsWith("queue-"))
    }
}
