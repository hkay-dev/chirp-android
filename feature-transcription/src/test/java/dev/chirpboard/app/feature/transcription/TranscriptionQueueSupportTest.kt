package dev.chirpboard.app.feature.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionQueueSupportTest {

    @Test
    fun `parseRecoveryMetadata returns nulls for blank error message`() {
        val parsed = parseRecoveryMetadata(null)

        assertNull(parsed.reason)
        assertNull(parsed.lastAttemptEpochMs)
    }

    @Test
    fun `parseRecoveryMetadata strips queue handoff prefix and preserves manual recovery payload`() {
        val parsed = parseRecoveryMetadata(
            "recoverable_queue_handoff:manual_recovery:retry me|attemptAt=1234"
        )

        assertEquals("retry me", parsed.reason)
        assertEquals(1234L, parsed.lastAttemptEpochMs)
    }

    @Test
    fun `parseRecoveryMetadata returns payload without timestamp when attempt marker is missing`() {
        val parsed = parseRecoveryMetadata("manual_recovery:requeue me")

        assertEquals("requeue me", parsed.reason)
        assertNull(parsed.lastAttemptEpochMs)
    }

    @Test
    fun `buildManualRecoveryMessage prefixes reason and timestamp marker`() {
        val message = buildManualRecoveryMessage("needs requeue")

        assertTrue(message.startsWith("manual_recovery:needs requeue|attemptAt="))
    }
}
