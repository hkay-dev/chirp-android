package dev.chirpboard.app.feature.keyboard.service

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class KeyboardInputConnectionActionsTest {
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
        every { connection.getTextBeforeCursor(2, 0) } returns "a"
        every { connection.deleteSurroundingText(1, 0) } returns true

        deletePreviousCharacter(connection)

        verify { connection.deleteSurroundingText(1, 0) }
    }

    @Test
    fun `deletePreviousCharacter deletes surrogate pair`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns ""
        every { connection.getTextBeforeCursor(2, 0) } returns "😀"
        every { connection.deleteSurroundingText(2, 0) } returns true

        deletePreviousCharacter(connection)

        verify { connection.deleteSurroundingText(2, 0) }
    }

    @Test
    fun `deletePreviousCharacter sends delete key when buffer empty`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.getSelectedText(0) } returns null
        every { connection.getTextBeforeCursor(2, 0) } returns ""

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
}
