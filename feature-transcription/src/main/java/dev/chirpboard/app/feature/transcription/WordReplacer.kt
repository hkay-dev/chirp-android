package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.data.entity.WordReplacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for applying word replacement rules to text.
 * Used during transcription to substitute words or phrases.
 */
@Singleton
class WordReplacer @Inject constructor() {

    /**
     * Apply all enabled replacements to the input text.
     * @param text Original text
     * @param replacements List of replacement rules (only enabled ones are applied)
     * @return Text with replacements applied
     */
    suspend fun apply(text: String, replacements: List<WordReplacement>): String = withContext(Dispatchers.Default) {
        var result = text
        for (rule in replacements.filter { it.enabled }) {
            val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val regex = Regex("\\b${Regex.escape(rule.original)}\\b", options)
            result = result.replace(regex, rule.replacement)
        }
        result
    }
}
