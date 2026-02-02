package dev.parakeeboard.app.feature.llm

import dev.parakeeboard.app.feature.llm.client.LlmClient
import dev.parakeeboard.app.feature.llm.model.ProcessingMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level text processor that uses LlmClient for processing.
 * Handles mode selection and smart mode detection.
 */
@Singleton
class TextProcessor @Inject constructor(
    private val llmClient: LlmClient
) {
    companion object {
        // Code-like patterns for Smart mode detection
        private val CODE_PATTERNS = listOf(
            "function", "def ", "class ", "var ", "const ", "let ",
            "public ", "private ", "protected ", "static ",
            "import ", "export ", "return ", "if (", "for (", "while (",
            "->", "=>", "::", "&&", "||", "==", "!="
        )
        private val CODE_REGEX = Regex("[(){}\\[\\];]")

        // Email-like patterns for Smart mode detection
        private val EMAIL_PATTERNS = listOf(
            "dear", "hi ", "hello", "regards", "sincerely",
            "best regards", "kind regards", "thank you", "thanks"
        )
    }

    /**
     * Process text with the specified processing mode.
     * @param text The input text to process
     * @param mode The processing mode to use
     * @return Result containing processed text or error
     */
    suspend fun process(text: String, mode: ProcessingMode): Result<String> {
        val prompt = resolvePrompt(text, mode)
        return llmClient.process(text, prompt)
    }

    /**
     * Generate a title for transcript text.
     * @param transcript The transcript to generate a title for
     * @return Result containing the generated title or error
     */
    suspend fun generateTitle(transcript: String): Result<String> {
        return llmClient.generateTitle(transcript)
    }

    /**
     * Generate a summary for transcript text.
     * @param transcript The transcript to summarize
     * @return Result containing the generated summary or error
     */
    suspend fun generateSummary(transcript: String): Result<String> {
        return llmClient.generateSummary(transcript)
    }

    private fun resolvePrompt(text: String, mode: ProcessingMode): String {
        return when (mode) {
            is ProcessingMode.Smart -> detectContentType(text).prompt!!
            is ProcessingMode.Custom -> mode.customPrompt
            else -> mode.prompt!!
        }
    }

    private fun detectContentType(text: String): ProcessingMode {
        val lowerText = text.lowercase()

        // Check for email patterns
        if (EMAIL_PATTERNS.any { lowerText.contains(it) }) {
            return ProcessingMode.Email
        }

        // Check for code patterns
        if (CODE_PATTERNS.any { lowerText.contains(it) } || CODE_REGEX.containsMatchIn(text)) {
            return ProcessingMode.Code
        }

        // Default to Formal
        return ProcessingMode.Formal
    }
}
