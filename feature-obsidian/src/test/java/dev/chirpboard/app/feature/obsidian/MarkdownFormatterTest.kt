package dev.chirpboard.app.feature.obsidian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.Month

class MarkdownFormatterTest {

    @Test
    fun `format generates valid markdown with yaml frontmatter`() {
        val date = LocalDateTime.of(2024, Month.JANUARY, 15, 10, 30, 0)

        val result = MarkdownFormatter.format(
            title = "Test Recording",
            transcript = "This is the transcript.",
            summary = "A short summary",
            date = date,
            durationSeconds = 120,
            tags = listOf("tag1", "test:tag"),
            source = "app"
        )

        val expected = """
            ---
            title: "Test Recording"
            date: 2024-01-15T10:30:00
            duration: 120
            tags: ["tag1", "test:tag"]
            summary: "A short summary"
            source: "app"
            ---

            ## Transcript

            This is the transcript.

        """.trimIndent()

        assertEquals(expected, result)
    }

    @Test
    fun `format escapes yaml strings correctly`() {
        val date = LocalDateTime.of(2024, Month.JANUARY, 15, 10, 30, 0)

        val result = MarkdownFormatter.format(
            title = "Title with: colon",
            transcript = "Transcript",
            summary = "Summary with \"quotes\"",
            date = date,
            durationSeconds = 60,
            tags = emptyList(),
            source = "keyboard"
        )

        val expected = """
            ---
            title: "Title with: colon"
            date: 2024-01-15T10:30:00
            duration: 60
            tags: []
            summary: "Summary with \"quotes\""
            source: "keyboard"
            ---

            ## Transcript

            Transcript

        """.trimIndent()

        assertEquals(expected, result)
    }

    @Test
    fun `format omits summary if null`() {
        val date = LocalDateTime.of(2024, Month.JANUARY, 15, 10, 30, 0)

        val result = MarkdownFormatter.format(
            title = "Title",
            transcript = "Transcript",
            summary = null,
            date = date,
            durationSeconds = 60,
            tags = listOf("tag"),
            source = "app"
        )

        val expected = """
            ---
            title: "Title"
            date: 2024-01-15T10:30:00
            duration: 60
            tags: ["tag"]
            source: "app"
            ---

            ## Transcript

            Transcript

        """.trimIndent()

        assertEquals(expected, result)
    }

    @Test
    fun `format quotes titles that yaml would otherwise coerce or misparse`() {
        val date = LocalDateTime.of(2024, Month.JANUARY, 15, 10, 30, 0)

        // "true" as a plain scalar becomes a boolean, "- item" a list entry: quoting
        // unconditionally keeps every title a string.
        val coerced = MarkdownFormatter.format(
            title = "true",
            transcript = "Transcript",
            summary = "- looks like a list item",
            date = date,
            durationSeconds = 60,
            tags = emptyList(),
            source = "app"
        )
        assertTrue(coerced.contains("title: \"true\"\n"))
        assertTrue(coerced.contains("summary: \"- looks like a list item\"\n"))
    }

    @Test
    fun `format escapes carriage returns and tabs inside yaml strings`() {
        val date = LocalDateTime.of(2024, Month.JANUARY, 15, 10, 30, 0)

        val result = MarkdownFormatter.format(
            title = "line one\r\nline two\ttabbed",
            transcript = "Transcript",
            summary = null,
            date = date,
            durationSeconds = 60,
            tags = emptyList(),
            source = "app"
        )

        // A raw \r or \t inside a quoted scalar breaks the frontmatter block.
        assertTrue(result.contains("title: \"line one\\r\\nline two\\ttabbed\"\n"))
    }
}
