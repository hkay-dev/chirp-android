package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.data.entity.WordReplacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for applying word replacement rules to text.
 * Used during transcription to substitute words or phrases.
 */
@Singleton
class WordReplacer @Inject constructor() {

    // Rules are stable user settings applied once per finished utterance (including
    // every keyboard dictation), so compiled patterns are cached across calls.
    private val compiledRules = ConcurrentHashMap<RuleKey, Regex>()

    private data class RuleKey(val original: String, val caseSensitive: Boolean)

    /**
     * Apply all enabled replacements to the input text.
     * @param text Original text
     * @param replacements List of replacement rules (only enabled ones are applied)
     * @return Text with replacements applied
     */
    suspend fun apply(text: String, replacements: List<WordReplacement>): String = withContext(Dispatchers.Default) {
        var result = text
        for (rule in replacements.filter { it.enabled && it.original.isNotEmpty() }) {
            val regex = compiledRules.getOrPut(RuleKey(rule.original, rule.caseSensitive)) { compile(rule) }
            result = result.replace(regex, Regex.escapeReplacement(rule.replacement))
        }
        result
    }

    private fun compile(rule: WordReplacement): Regex {
        val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        // \b only exists next to a word character, so anchoring a rule that starts or
        // ends with punctuation (".NET", "C++") would make it never match at all.
        val prefix = if (rule.original.first().isWordChar()) "\\b" else ""
        val suffix = if (rule.original.last().isWordChar()) "\\b" else ""
        return Regex(prefix + Regex.escape(rule.original) + suffix, options)
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'
}
