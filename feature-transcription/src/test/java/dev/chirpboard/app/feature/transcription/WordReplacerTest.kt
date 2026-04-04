package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.data.entity.WordReplacement
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WordReplacerTest {

    private lateinit var classUnderTest: WordReplacer

    @Before
    fun setup() {
        classUnderTest = WordReplacer()
    }

    @Test
    fun `apply returns original text when no replacements provided`() {
        val result = classUnderTest.apply("hello world", emptyList())
        assertEquals("hello world", result)
    }

    @Test
    fun `apply ignores disabled replacements`() {
        val rule = WordReplacement(original = "world", replacement = "there", enabled = false)
        val result = classUnderTest.apply("hello world", listOf(rule))
        assertEquals("hello world", result)
    }

    @Test
    fun `apply performs case sensitive replacement`() {
        val rule = WordReplacement(original = "World", replacement = "There", enabled = true, caseSensitive = true)
        val result1 = classUnderTest.apply("hello World", listOf(rule))
        assertEquals("hello There", result1)

        val result2 = classUnderTest.apply("hello world", listOf(rule))
        assertEquals("hello world", result2) // No replacement
    }

    @Test
    fun `apply performs case insensitive replacement`() {
        val rule = WordReplacement(original = "world", replacement = "there", enabled = true, caseSensitive = false)
        val result = classUnderTest.apply("hello WORLD", listOf(rule))
        assertEquals("hello there", result)
    }

    @Test
    fun `apply applies multiple replacements in order`() {
        val rules = listOf(
            WordReplacement(original = "hello", replacement = "hi", enabled = true),
            WordReplacement(original = "hi world", replacement = "greetings", enabled = true)
        )
        val result = classUnderTest.apply("hello world", rules)
        assertEquals("greetings", result)
    }
}
