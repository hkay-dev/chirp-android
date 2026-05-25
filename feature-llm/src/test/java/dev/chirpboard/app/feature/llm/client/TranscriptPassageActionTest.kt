package dev.chirpboard.app.feature.llm.client

import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPassageActionTest {
    @Test
    fun `buildTranscriptPassagePrompt keeps summarize scoped to selected passage`() {
        val prompt =
            buildTranscriptPassagePrompt(
                action = TranscriptPassageAction.SUMMARIZE,
                passage = "launch is blocked on legal review",
            )

        assertTrue(prompt.contains("selected passage", ignoreCase = true))
        assertTrue(prompt.contains("launch is blocked on legal review"))
    }

    @Test
    fun `buildTranscriptPassagePrompt asks extract action for concrete items`() {
        val prompt =
            buildTranscriptPassagePrompt(
                action = TranscriptPassageAction.EXTRACT_ITEMS,
                passage = "follow up with Sam tomorrow and send the revised draft",
            )

        assertTrue(prompt.contains("action items", ignoreCase = true))
        assertTrue(prompt.contains("follow up with Sam tomorrow and send the revised draft"))
    }
}
