package dev.chirpboard.app.feature.llm.generator

import dev.chirpboard.app.feature.llm.client.LlmClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TitleGenerator @Inject constructor(
    private val llmClient: LlmClient
) {
    /**
     * Generate a concise title from transcript text.
     * Uses first ~500 words to generate a 3-8 word title.
     * @param transcript Full transcript text
     * @return Generated title or null if generation fails
     */
    suspend fun generate(transcript: String): String? {
        if (transcript.isBlank()) return null

        val truncated = transcript.split(" ").take(500).joinToString(" ")
        val prompt = """Generate a concise, descriptive title (3-8 words) for this recording transcript.
            |Return ONLY the title, nothing else.
            |
            |Transcript:
            |$truncated""".trimMargin()

        return llmClient.process(truncated, prompt).getOrNull()?.trim()
    }
}
