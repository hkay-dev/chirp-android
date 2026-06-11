package dev.chirpboard.app.feature.keyboard.service

import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StraySwitchCharacterCleanupTest {
    @Test
    fun `lone z at start of field is stray`() {
        assertTrue(endsWithStandaloneStrayCharacter("z"))
        assertTrue(endsWithStandaloneStrayCharacter("Z"))
    }

    @Test
    fun `z after whitespace or punctuation is stray`() {
        assertTrue(endsWithStandaloneStrayCharacter("hello z"))
        assertTrue(endsWithStandaloneStrayCharacter("done.z"))
        assertTrue(endsWithStandaloneStrayCharacter("line\nZ"))
    }

    @Test
    fun `z ending a word is not stray`() {
        assertFalse(endsWithStandaloneStrayCharacter("buzz"))
        assertFalse(endsWithStandaloneStrayCharacter("Jazz"))
        assertFalse(endsWithStandaloneStrayCharacter("a1z"))
    }

    @Test
    fun `other trailing characters are not stray`() {
        assertFalse(endsWithStandaloneStrayCharacter(""))
        assertFalse(endsWithStandaloneStrayCharacter("hello "))
        assertFalse(endsWithStandaloneStrayCharacter("hello a"))
    }

    @Test
    fun `stray z before cursor is deleted`() {
        val connection = mockk<InputConnection>()
        every { connection.getSelectedText(0) } returns null
        every { connection.getTextBeforeCursor(2, 0) } returns " z"
        every { connection.deleteSurroundingText(1, 0) } returns true

        assertTrue(removeStraySwitchCharacter(connection))

        verify { connection.deleteSurroundingText(1, 0) }
    }

    @Test
    fun `word ending in z is left alone`() {
        val connection = mockk<InputConnection>()
        every { connection.getSelectedText(0) } returns null
        every { connection.getTextBeforeCursor(2, 0) } returns "zz"

        assertFalse(removeStraySwitchCharacter(connection))

        verify(exactly = 0) { connection.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun `active selection is left alone`() {
        val connection = mockk<InputConnection>()
        every { connection.getSelectedText(0) } returns "selected"

        assertFalse(removeStraySwitchCharacter(connection))

        verify(exactly = 0) { connection.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun `null connection is a no-op`() {
        assertFalse(removeStraySwitchCharacter(null))
    }
}
