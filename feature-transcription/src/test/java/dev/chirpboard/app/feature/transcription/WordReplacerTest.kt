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
    fun `apply returns original text when no replacements provided`() = kotlinx.coroutines.test.runTest {
        val result = classUnderTest.apply("hello world", emptyList())
        assertEquals("hello world", result)
    }

    @Test
    fun `apply ignores disabled replacements`() = kotlinx.coroutines.test.runTest {
        val rule = WordReplacement(original = "world", replacement = "there", enabled = false)
        val result = classUnderTest.apply("hello world", listOf(rule))
        assertEquals("hello world", result)
    }

    @Test
    fun `apply performs case sensitive replacement`() = kotlinx.coroutines.test.runTest {
        val rule = WordReplacement(original = "World", replacement = "There", enabled = true, caseSensitive = true)
        val result1 = classUnderTest.apply("hello World", listOf(rule))
        assertEquals("hello There", result1)

        val result2 = classUnderTest.apply("hello world", listOf(rule))
        assertEquals("hello world", result2) // No replacement
    }

    @Test
    fun `apply performs case insensitive replacement`() = kotlinx.coroutines.test.runTest {
        val rule = WordReplacement(original = "world", replacement = "there", enabled = true, caseSensitive = false)
        val result = classUnderTest.apply("hello WORLD", listOf(rule))
        assertEquals("hello there", result)
    }

    @Test
    fun `apply applies multiple replacements in order`() = kotlinx.coroutines.test.runTest {
        val rules = listOf(
            WordReplacement(original = "hello", replacement = "hi", enabled = true),
            WordReplacement(original = "hi world", replacement = "greetings", enabled = true)
        )
        val result = classUnderTest.apply("hello world", rules)
        assertEquals("greetings", result)
    }

    @Test
    fun `apply matches rules that start or end with punctuation`() = kotlinx.coroutines.test.runTest {
        val rules = listOf(
            WordReplacement(original = "dot net", replacement = ".NET", enabled = true),
            WordReplacement(original = "C++", replacement = "C plus plus", enabled = true)
        )
        val result = classUnderTest.apply("I use dot net and C++ daily", rules)
        assertEquals("I use .NET and C plus plus daily", result)
    }

    @Test
    fun `apply does not replace inside larger words`() = kotlinx.coroutines.test.runTest {
        val rule = WordReplacement(original = "cat", replacement = "dog", enabled = true)
        val result = classUnderTest.apply("concatenate the cat", listOf(rule))
        assertEquals("concatenate the dog", result)
    }

    @Test
    fun `apply treats dollar signs and backslashes in the replacement literally`() = kotlinx.coroutines.test.runTest {
        val rule = WordReplacement(original = "price", replacement = "$5 \\ up", enabled = true)
        val result = classUnderTest.apply("the price today", listOf(rule))
        assertEquals("the $5 \\ up today", result)
    }

    @Test
    fun `apply ignores rules with an empty original`() = kotlinx.coroutines.test.runTest {
        val rule = WordReplacement(original = "", replacement = "x", enabled = true)
        val result = classUnderTest.apply("hello world", listOf(rule))
        assertEquals("hello world", result)
    }
}
