package dev.chirpboard.app.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TranscriptTest {
    @Test
    fun `effectiveText prefers saved manual correction`() {
        val transcript =
            Transcript(
                recordingId = UUID.randomUUID(),
                rawText = "raw text",
                processedText = "processed text",
                manualCorrectionText = "manual text",
            )

        assertEquals("manual text", transcript.effectiveText)
        assertEquals("processed text", transcript.pipelineText)
        assertTrue(transcript.hasManualCorrection)
    }

    @Test
    fun `effectiveText falls back to pipeline text`() {
        val transcript =
            Transcript(
                recordingId = UUID.randomUUID(),
                rawText = "raw text",
                processedText = "processed text",
            )

        assertEquals("processed text", transcript.effectiveText)
        assertEquals("processed text", transcript.pipelineText)
    }
}
