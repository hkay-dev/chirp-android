package dev.chirpboard.app.feature.llm.client

enum class TranscriptPassageAction {
    SUMMARIZE,
    EXPLAIN,
    EXTRACT_ITEMS,
}

internal fun buildTranscriptPassagePrompt(
    action: TranscriptPassageAction,
    passage: String,
): String =
    when (action) {
        TranscriptPassageAction.SUMMARIZE -> {
            """
            Summarize this selected passage from a voice recording transcript in 2-3 concise sentences.
            Stay grounded in the selected passage only.
            Return only the summary.

            <selected_passage>
            $passage
            </selected_passage>
            """.trimIndent()
        }

        TranscriptPassageAction.EXPLAIN -> {
            """
            Explain this selected passage from a voice recording transcript in plain language.
            Clarify jargon, references, or implied meaning, but stay grounded in the selected passage only.
            Return only the explanation.

            <selected_passage>
            $passage
            </selected_passage>
            """.trimIndent()
        }

        TranscriptPassageAction.EXTRACT_ITEMS -> {
            """
            Extract concrete action items, tasks, decisions, or follow-ups from this selected passage from a voice recording transcript.
            Return a short bullet list.
            If there are no clear items, return exactly: No action items found.

            <selected_passage>
            $passage
            </selected_passage>
            """.trimIndent()
        }
    }
