package dev.chirpboard.app.feature.keyboard.service

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemeBoundariesTest {
    @Test
    fun `plain ascii clusters are single units`() {
        assertEquals(1, GraphemeBoundaries.trailingClusterLength("hello"))
        assertEquals(0, GraphemeBoundaries.trailingClusterLength(""))
    }

    @Test
    fun `surrogate pair emoji is one cluster`() {
        assertEquals(2, GraphemeBoundaries.trailingClusterLength("😀"))
        assertEquals(2, GraphemeBoundaries.trailingClusterLength("a😀"))
    }

    @Test
    fun `flag emoji deletes both regional indicators`() {
        val flag = "🇺🇸" // two regional indicators, 4 UTF-16 units
        assertEquals(4, GraphemeBoundaries.trailingClusterLength(flag))
        assertEquals(4, GraphemeBoundaries.trailingClusterLength("go $flag"))
    }

    @Test
    fun `adjacent flags split into pairs`() {
        val flags = "🇺🇸🇫🇷" // four RIs; the boundary is between the two flags
        assertEquals(4, GraphemeBoundaries.trailingClusterLength(flags))
        assertEquals(4, GraphemeBoundaries.previousBoundary(flags, flags.length))
    }

    @Test
    fun `skin tone modifier stays attached`() {
        val thumbsUp = "👍🏽" // base + emoji modifier = 4 units
        assertEquals(4, GraphemeBoundaries.trailingClusterLength(thumbsUp))
    }

    @Test
    fun `zwj family is one cluster`() {
        val family = "👨‍👩‍👧‍👦" // 11 UTF-16 units
        assertEquals(11, GraphemeBoundaries.trailingClusterLength(family))
        assertEquals(11, GraphemeBoundaries.trailingClusterLength("we $family"))
    }

    @Test
    fun `variation selector heart is one cluster`() {
        val heart = "❤️"
        assertEquals(2, GraphemeBoundaries.trailingClusterLength(heart))
    }

    @Test
    fun `combining mark stays with its base`() {
        val decomposed = "é" // NFD é
        assertEquals(2, GraphemeBoundaries.trailingClusterLength(decomposed))
        assertEquals(2, GraphemeBoundaries.trailingClusterLength("café"))
    }

    @Test
    fun `keycap sequence is one cluster`() {
        val keycap = "1️⃣"
        assertEquals(3, GraphemeBoundaries.trailingClusterLength(keycap))
    }

    @Test
    fun `crlf is one cluster`() {
        assertEquals(2, GraphemeBoundaries.trailingClusterLength("a\r\n"))
    }

    @Test
    fun `hangul syllable jamo compose`() {
        val jamo = "각" // L V T
        assertEquals(3, GraphemeBoundaries.trailingClusterLength(jamo))
    }

    @Test
    fun `next boundary steps over whole clusters`() {
        val text = "a🇺🇸b"
        assertEquals(1, GraphemeBoundaries.nextBoundary(text, 0))
        assertEquals(5, GraphemeBoundaries.nextBoundary(text, 1))
        assertEquals(6, GraphemeBoundaries.nextBoundary(text, 5))
        assertEquals(text.length, GraphemeBoundaries.nextBoundary(text, text.length))
    }

    @Test
    fun `previous boundary steps over whole clusters`() {
        val text = "a👍🏽b"
        assertEquals(5, GraphemeBoundaries.previousBoundary(text, 6))
        assertEquals(1, GraphemeBoundaries.previousBoundary(text, 5))
        assertEquals(0, GraphemeBoundaries.previousBoundary(text, 1))
        assertEquals(0, GraphemeBoundaries.previousBoundary(text, 0))
    }

    @Test
    fun `zwj sequence cursor never parks inside`() {
        val text = "x👩‍💻y" // woman technologist: 5 units
        assertEquals(1, GraphemeBoundaries.previousBoundary(text, 6))
        assertEquals(6, GraphemeBoundaries.nextBoundary(text, 1))
    }
}
