package dev.chirpboard.app.feature.llm.client

class TranscriptLlmContext(
    val transcript: String,
    val providerId: String? = null,
    val modelId: String? = null,
) {
    /**
     * Assembles the processing prompt with the transcript always fully delimited.
     * Built-in prompts end with an opening `<transcript>` tag and only need the data plus
     * the closing tag; user presets and Custom prompts are free text, so the opening tag
     * (and a separating blank line) is added here — otherwise dictated speech would be
     * concatenated straight onto the instructions with an unbalanced closing tag, losing
     * the data/instruction separation the built-in prompts' safety protocol depends on.
     */
    fun processPrompt(systemPrompt: String): String {
        val prefix =
            if (systemPrompt.trimEnd().endsWith(OPENING_TRANSCRIPT_TAG)) {
                systemPrompt
            } else {
                "$systemPrompt\n\n$OPENING_TRANSCRIPT_TAG\n"
            }
        return "$prefix$transcript\n$CLOSING_TRANSCRIPT_TAG"
    }

    fun prefixedPrompt(prefix: String): String = prefix + transcript

    private companion object {
        private const val OPENING_TRANSCRIPT_TAG = "<transcript>"
        private const val CLOSING_TRANSCRIPT_TAG = "</transcript>"
    }
}
