package dev.parakeeboard.app.feature.obsidian

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Formats recording data as Obsidian-compatible Markdown with YAML frontmatter.
 */
object MarkdownFormatter {

    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * Format recording as Obsidian-compatible Markdown with YAML frontmatter.
     *
     * Output format:
     * ```
     * ---
     * title: Recording Title
     * date: 2024-01-15T10:30:00
     * duration: 125
     * tags: [tag1, tag2]
     * summary: One-line summary
     * source: app
     * ---
     *
     * ## Transcript
     *
     * The transcript text...
     * ```
     *
     * @param title The recording title
     * @param transcript The transcript text
     * @param summary Optional one-line summary
     * @param date When the recording was created
     * @param durationSeconds Recording duration in seconds
     * @param tags List of tag names
     * @param source Where the recording was created (e.g., "app", "keyboard")
     * @return Formatted Markdown string
     */
    fun format(
        title: String,
        transcript: String,
        summary: String?,
        date: LocalDateTime,
        durationSeconds: Long,
        tags: List<String>,
        source: String
    ): String = buildString {
        // YAML frontmatter
        appendLine("---")
        appendLine("title: ${escapeYamlString(title)}")
        appendLine("date: ${date.format(isoFormatter)}")
        appendLine("duration: $durationSeconds")
        appendLine("tags: ${formatYamlList(tags)}")
        if (summary != null) {
            appendLine("summary: ${escapeYamlString(summary)}")
        }
        appendLine("source: $source")
        appendLine("---")
        appendLine()

        // Transcript section
        appendLine("## Transcript")
        appendLine()
        appendLine(transcript.trim())
    }

    /**
     * Escape special characters in YAML strings.
     * Wraps in quotes if the string contains special characters.
     */
    private fun escapeYamlString(value: String): String {
        val needsQuotes = value.any { it in listOf(':', '#', '[', ']', '{', '}', ',', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`') } ||
            value.startsWith(' ') ||
            value.endsWith(' ') ||
            value.contains('\n')

        return if (needsQuotes) {
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        } else {
            value
        }
    }

    /**
     * Format a list as YAML inline array.
     */
    private fun formatYamlList(items: List<String>): String {
        if (items.isEmpty()) return "[]"
        return items.joinToString(prefix = "[", postfix = "]") { escapeYamlString(it) }
    }
}
