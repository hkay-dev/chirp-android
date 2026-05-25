package dev.chirpboard.app.feature.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStudioShareTest {
    @Test
    fun `buildTranscriptShareText includes summary and transcript sections`() {
        val text =
            ProcessingStudioShare.buildTranscriptShareText(
                title = "Meeting notes",
                summary = "Quick recap",
                transcriptText = "Hello world",
            )

        assertTrue(text.contains("# Meeting notes"))
        assertTrue(text.contains("## Summary"))
        assertTrue(text.contains("Quick recap"))
        assertTrue(text.contains("## Transcript"))
        assertTrue(text.contains("Hello world"))
    }

    @Test
    fun `buildStructuredOutcomeShareText includes group label`() {
        val text =
            ProcessingStudioShare.buildStructuredOutcomeShareText(
                title = "Meeting notes",
                groupLabel = "Tasks",
                itemText = "Follow up with Alex",
            )

        assertEquals(
            """
            # Meeting notes

            ## Tasks
            Follow up with Alex
            """.trimIndent(),
            text.trim(),
        )
    }
}
