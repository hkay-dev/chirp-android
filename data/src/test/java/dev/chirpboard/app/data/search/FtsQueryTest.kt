package dev.chirpboard.app.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsQueryTest {
    @Test
    fun `builds a prefix term per word`() {
        assertEquals("budget* review*", FtsQuery.toFtsPrefixMatchQuery("budget review"))
    }

    @Test
    fun `lowercases so FTS keywords lose their operator meaning`() {
        assertEquals("cats* or* dogs*", FtsQuery.toFtsPrefixMatchQuery("cats OR dogs"))
        assertEquals("a* near* b*", FtsQuery.toFtsPrefixMatchQuery("a NEAR b"))
        assertEquals("x* not* y*", FtsQuery.toFtsPrefixMatchQuery("x NOT y"))
    }

    @Test
    fun `strips the operators that would make MATCH a syntax error`() {
        assertEquals("re* org*", FtsQuery.toFtsPrefixMatchQuery("""-"re-org"""))
        assertEquals("a* b*", FtsQuery.toFtsPrefixMatchQuery("(a b"))
        assertEquals("col* value*", FtsQuery.toFtsPrefixMatchQuery("col:value"))
        assertEquals("head*", FtsQuery.toFtsPrefixMatchQuery("^head"))
        assertEquals("star*", FtsQuery.toFtsPrefixMatchQuery("star**"))
    }

    @Test
    fun `collapses runs of separators instead of emitting empty terms`() {
        assertEquals("one* two*", FtsQuery.toFtsPrefixMatchQuery("  one   ---  two  "))
    }

    @Test
    fun `keeps digits and non-latin letters`() {
        assertEquals("q3* 2026*", FtsQuery.toFtsPrefixMatchQuery("Q3 2026"))
        assertEquals("привет*", FtsQuery.toFtsPrefixMatchQuery("привет"))
    }

    @Test
    fun `returns empty for input with no word characters`() {
        assertEquals("", FtsQuery.toFtsPrefixMatchQuery(""))
        assertEquals("", FtsQuery.toFtsPrefixMatchQuery("   "))
        assertEquals("", FtsQuery.toFtsPrefixMatchQuery("""-"*^()"""))
    }

    @Test
    fun `caps the number of terms`() {
        val query = (1..40).joinToString(" ") { "w$it" }

        val terms = FtsQuery.toFtsPrefixMatchQuery(query).split(" ")

        assertEquals(16, terms.size)
        assertEquals("w1*", terms.first())
        assertEquals("w16*", terms.last())
    }
}
