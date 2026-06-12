package dev.chirpboard.app.feature.keyboard.service

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class KeyboardInputConnectionActionsTest {
    private companion object {
        // Mirrors the grapheme context window used by deletePreviousCharacter.
        const val GRAPHEME_WINDOW = 64
    }

    @Test
    fun `deletePreviousCharacter deletes selected text`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns "hello"

        deletePreviousCharacter(connection)

        verify { connection.commitText("", 1) }
    }

    @Test
    fun `deletePreviousCharacter deletes one code unit`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(GRAPHEME_WINDOW, 0) } returns "a"
        every { connection.deleteSurroundingText(1, 0) } returns true

        deletePreviousCharacter(connection)

        verify { connection.deleteSurroundingText(1, 0) }
    }

    @Test
    fun `deletePreviousCharacter deletes surrogate pair`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(GRAPHEME_WINDOW, 0) } returns "😀"
        every { connection.deleteSurroundingText(2, 0) } returns true

        deletePreviousCharacter(connection)

        verify { connection.deleteSurroundingText(2, 0) }
    }

    @Test
    fun `deletePreviousCharacter deletes whole flag emoji`() {
        // IME-8: one backspace removes both regional indicators, never leaving a lone "🇺".
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(GRAPHEME_WINDOW, 0) } returns "go 🇺🇸"
        every { connection.deleteSurroundingText(4, 0) } returns true

        deletePreviousCharacter(connection)

        verify { connection.deleteSurroundingText(4, 0) }
    }

    @Test
    fun `deletePreviousCharacter deletes whole zwj family`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(GRAPHEME_WINDOW, 0) } returns "we 👨‍👩‍👧‍👦"
        every { connection.deleteSurroundingText(11, 0) } returns true

        deletePreviousCharacter(connection)

        verify { connection.deleteSurroundingText(11, 0) }
    }

    @Test
    fun `deletePreviousCharacter deletes skin tone emoji wholly`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(GRAPHEME_WINDOW, 0) } returns "👍🏽"
        every { connection.deleteSurroundingText(4, 0) } returns true

        deletePreviousCharacter(connection)

        verify { connection.deleteSurroundingText(4, 0) }
    }

    @Test
    fun `deletePreviousCharacter deletes variation selector heart wholly`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(GRAPHEME_WINDOW, 0) } returns "❤️"
        every { connection.deleteSurroundingText(2, 0) } returns true

        deletePreviousCharacter(connection)

        verify { connection.deleteSurroundingText(2, 0) }
    }

    @Test
    fun `deletePreviousCharacter sends delete key when buffer empty`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns null
        every { connection.getTextBeforeCursor(GRAPHEME_WINDOW, 0) } returns ""

        deletePreviousCharacter(connection)

        verify(exactly = 2) { connection.sendKeyEvent(any()) }
    }

    @Test
    fun `commitSpace calls commitText with space`() {
        val connection = mockk<InputConnection>(relaxed = true)
        commitSpace(connection)
        verify { connection.finishComposingText() }
        verify { connection.commitText(" ", 1) }
    }

    @Test
    fun `deletePreviousWord removes previous word`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(512, 0) } returns "hello world"
        every { connection.deleteSurroundingText(5, 0) } returns true

        deletePreviousWord(connection)

        verify { connection.deleteSurroundingText(5, 0) }
    }

    @Test
    fun `deletePreviousWord skips trailing whitespace`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(512, 0) } returns "hello world  "
        every { connection.deleteSurroundingText(7, 0) } returns true

        deletePreviousWord(connection)

        verify { connection.deleteSurroundingText(7, 0) }
    }

    @Test
    fun `deletePreviousWord stops at punctuation boundary`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(512, 0) } returns "hello.world"
        every { connection.deleteSurroundingText(5, 0) } returns true

        deletePreviousWord(connection)

        verify { connection.deleteSurroundingText(5, 0) }
    }

    @Test
    fun `deletePreviousWord keeps combining marks inside the word`() {
        // IME-8: NFD "café" (e + combining acute) deletes as one word, not just the accent.
        val decomposed = "café"
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(512, 0) } returns decomposed
        every { connection.deleteSurroundingText(decomposed.length, 0) } returns true

        deletePreviousWord(connection)

        verify { connection.deleteSurroundingText(decomposed.length, 0) }
    }

    @Test
    fun `deletePreviousWord deletes selected text`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns "hello"

        deletePreviousWord(connection)

        verify { connection.commitText("", 1) }
    }

    @Test
    fun `moveCursor uses setSelection when extracted text available`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns
            ExtractedText().apply {
                text = "hello"
                selectionStart = 2
                selectionEnd = 2
            }
        every { connection.setSelection(3, 3) } returns true

        moveCursor(connection, 1)

        verify { connection.setSelection(3, 3) }
        verify(exactly = 0) { connection.sendKeyEvent(any()) }
    }

    @Test
    fun `moveCursor clamps at text end`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns
            ExtractedText().apply {
                text = "hello"
                selectionStart = 5
                selectionEnd = 5
            }

        moveCursor(connection, 1)

        verify(exactly = 0) { connection.setSelection(any(), any()) }
        verify(exactly = 0) { connection.sendKeyEvent(any()) }
    }

    @Test
    fun `moveCursor collapses selection to movement edge`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns
            ExtractedText().apply {
                text = "hello"
                selectionStart = 1
                selectionEnd = 4
            }
        every { connection.setSelection(4, 4) } returns true

        moveCursor(connection, 1)

        verify { connection.setSelection(4, 4) }
        verify(exactly = 0) { connection.setSelection(5, 5) }
    }

    @Test
    fun `moveCursor skips surrogate pair boundaries`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns
            ExtractedText().apply {
                text = "😀a"
                selectionStart = 0
                selectionEnd = 0
            }
        every { connection.setSelection(2, 2) } returns true

        moveCursor(connection, 1)

        verify { connection.setSelection(2, 2) }
        verify(exactly = 0) { connection.setSelection(1, 1) }
    }

    @Test
    fun `moveCursor steps over whole grapheme clusters`() {
        // IME-8: a cursor step never parks inside a flag's regional-indicator pair.
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns
            ExtractedText().apply {
                text = "a🇺🇸b"
                selectionStart = 1
                selectionEnd = 1
            }
        every { connection.setSelection(5, 5) } returns true

        moveCursor(connection, 1)

        verify { connection.setSelection(5, 5) }
    }

    @Test
    fun `moveCursor honors extract startOffset`() {
        // IME-17: extract offsets are window-relative; setSelection must add startOffset back.
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns
            ExtractedText().apply {
                text = "hello"
                startOffset = 100
                selectionStart = 2
                selectionEnd = 2
            }
        every { connection.setSelection(103, 103) } returns true

        moveCursor(connection, 1)

        verify { connection.setSelection(103, 103) }
    }

    @Test
    fun `moveCursor falls back to key events when no selection is reported`() {
        // IME-17: selectionStart == -1 means "no selection reported", not "cursor at 0".
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns
            ExtractedText().apply {
                text = "hello"
                selectionStart = -1
                selectionEnd = -1
            }

        moveCursor(connection, 1)

        verify(exactly = 0) { connection.setSelection(any(), any()) }
        verify(exactly = 2) { connection.sendKeyEvent(any()) }
    }

    @Test
    fun `moveCursor repairs out of bounds selection before moving`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns
            ExtractedText().apply {
                text = "hello"
                selectionStart = 8
                selectionEnd = 8
            }
        every { connection.setSelection(any(), any()) } returns true

        moveCursor(connection, -1)

        verify { connection.setSelection(5, 5) }
        verify { connection.setSelection(4, 4) }
    }

    @Test
    fun `moveCursor falls back to key events when extracted text unavailable`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns null

        moveCursor(connection, 2)

        verify(exactly = 4) { connection.sendKeyEvent(any()) }
    }

    @Test
    fun `moveCursor never requests the extract monitor flag`() {
        // IME-18: a one-shot read must not subscribe this IME to every later editor change.
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getExtractedText(any(), any()) } returns null

        moveCursor(connection, 1)

        verify {
            connection.getExtractedText(
                match { request -> request.flags == 0 && request.hintMaxChars > 0 },
                0,
            )
        }
    }
}
