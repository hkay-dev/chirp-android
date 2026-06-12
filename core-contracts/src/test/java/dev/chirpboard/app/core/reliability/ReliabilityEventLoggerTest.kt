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

    @Test
    fun `scoped logger fills bound fields and emits one event per call`() {
        ReliabilityEventLogger.clear()

        val recordingId = java.util.UUID.randomUUID()
        val scope =
            ReliabilityEventLogger.scoped(
                stage = ReliabilityStage.ENHANCEMENT,
                correlationId = "corr-scope",
                recordingId = recordingId,
            )

        scope.started("enhancement_started")
        scope.failure("enhancement_failed", message = "boom")

        val events = ReliabilityEventLogger.events.value
        assertEquals(2, events.size)

        val started = events[0]
        assertEquals(ReliabilityStage.ENHANCEMENT, started.stage)
        assertEquals(ReliabilityOutcome.STARTED, started.outcome)
        assertEquals("corr-scope", started.correlationId)
        assertEquals(recordingId, started.recordingId)
        assertEquals("enhancement_started", started.reasonCode)
        assertEquals(null, started.message)

        val failed = events[1]
        assertEquals(ReliabilityOutcome.FAILURE, failed.outcome)
        assertEquals("enhancement_failed", failed.reasonCode)
        assertEquals("boom", failed.message)
    }

    @Test
    fun `scoped logger prefixes reason codes when a reason prefix is bound`() {
        ReliabilityEventLogger.clear()

        val scope =
            ReliabilityEventLogger.scoped(
                stage = ReliabilityStage.TRANSCRIPTION,
                correlationId = "inline-1",
                reasonPrefix = "ime",
            )

        scope.started("transcription_started")
        scope.success("transcription_completed")
        scope.skipped("no_speech")

        val events = ReliabilityEventLogger.events.value
        assertEquals(3, events.size)
        assertEquals("ime_transcription_started", events[0].reasonCode)
        assertEquals("ime_transcription_completed", events[1].reasonCode)
        assertEquals("ime_no_speech", events[2].reasonCode)
        assertEquals(ReliabilityStage.TRANSCRIPTION, events[0].stage)
        assertEquals("inline-1", events[0].correlationId)
        assertEquals(null, events[0].recordingId)
    }

    @Test
    fun `scoped failure overload captures throwable message`() {
        ReliabilityEventLogger.clear()

        val scope =
            ReliabilityEventLogger.scoped(
                stage = ReliabilityStage.QUEUE_ENQUEUE,
                correlationId = "corr-throw",
            )

        scope.failure("enqueue_exception", IllegalStateException("queue exploded"))

        val event = ReliabilityEventLogger.events.value.single()
        assertEquals(ReliabilityOutcome.FAILURE, event.outcome)
        assertEquals("enqueue_exception", event.reasonCode)
        assertEquals("queue exploded", event.message)
    }
}
