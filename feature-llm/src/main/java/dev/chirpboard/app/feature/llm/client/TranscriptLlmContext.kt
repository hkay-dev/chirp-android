package dev.chirpboard.app.feature.llm.client

class TranscriptLlmContext(
    val transcript: String,
    val providerId: String? = null,
    val modelId: String? = null,
) {
    private val transcriptWithClosingTag: String = transcript + "\n</transcript>"

    fun processPrompt(systemPrompt: String): String = systemPrompt + transcriptWithClosingTag

    fun prefixedPrompt(prefix: String): String = prefix + transcript
}
