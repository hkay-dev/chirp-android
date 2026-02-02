package dev.parakeeboard.app.feature.transcription

import dev.parakeeboard.app.data.entity.WordReplacement
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
    fun apply(text: String, replacements: List<WordReplacement>): String {
        var result = text
        for (rule in replacements.filter { it.enabled }) {
            result = if (rule.caseSensitive) {
                result.replace(rule.original, rule.replacement)
            } else {
                result.replace(rule.original, rule.replacement, ignoreCase = true)
            }
        }
        return result
    }
}
