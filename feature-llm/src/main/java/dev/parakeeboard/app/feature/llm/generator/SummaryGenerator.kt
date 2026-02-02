package dev.parakeeboard.app.feature.llm.generator

import dev.parakeeboard.app.feature.llm.client.LlmClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryGenerator @Inject constructor(
    private val llmClient: LlmClient
) {
    /**
     * Generate a 1-2 sentence summary for the recordings list subline.
     * @param transcript Full transcript text
     * @return Generated summary or null if generation fails
     */
    suspend fun generate(transcript: String): String? {
        if (transcript.isBlank()) return null

        val prompt = """Summarize this transcript in 1-2 short sentences (max 100 characters).
            |This will be shown as a preview in a list. Be concise and informative.
            |Return ONLY the summary, nothing else.
            |
            |Transcript:
            |$transcript""".trimMargin()

        return llmClient.process(transcript, prompt).getOrNull()?.trim()
    }
}
