package dev.chirpboard.app.feature.keyboard.service

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class KeyboardInputConnectionActionsTest {
    @Test
    fun `deletePreviousCharacter calls deleteSurroundingText`() {
        val connection = mockk<InputConnection>(relaxed = true)
        deletePreviousCharacter(connection)
        verify { connection.deleteSurroundingText(1, 0) }
    }

    @Test
    fun `commitSpace calls commitText with space`() {
        val connection = mockk<InputConnection>(relaxed = true)
        commitSpace(connection)
        verify { connection.commitText(" ", 1) }
    }

    @Test
    fun `moveCursor moves selection within bounds`() {
        val connection = mockk<InputConnection>(relaxed = true)
        val extractedText =
            ExtractedText().apply {
                text = "Hello"
                selectionStart = 2
                selectionEnd = 2
            }
        every { connection.getExtractedText(any(), any()) } returns extractedText

        moveCursor(connection, 2)

        verify { connection.setSelection(4, 4) }
    }
}
