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

    @Test
    fun `cleanup is attempted only within the post-create freshness window`() {
        // IME-5: a bind right after service creation can be an IME switch; later binds are
        // ordinary app switches where a trailing z/Z is legitimate text.
        assertTrue(shouldAttemptStraySwitchCleanup(0L))
        assertTrue(shouldAttemptStraySwitchCleanup(2_999L))
        assertTrue(shouldAttemptStraySwitchCleanup(STRAY_SWITCH_CLEANUP_FRESHNESS_MS))
        assertFalse(shouldAttemptStraySwitchCleanup(STRAY_SWITCH_CLEANUP_FRESHNESS_MS + 1))
        assertFalse(shouldAttemptStraySwitchCleanup(60_000L))
        assertFalse(shouldAttemptStraySwitchCleanup(-1L))
    }

    @Test
    fun `arming is skipped when the process died recently`() {
        // A system restart of the current IME after a kill recreates the service and binds
        // within the freshness window, mimicking an IME switch; a legitimate trailing z/Z
        // must not be deleted then.
        val now = 1_000_000L
        assertTrue(shouldArmStraySwitchCleanup(lastProcessExitTimestampMs = null, nowMs = now))
        assertFalse(shouldArmStraySwitchCleanup(lastProcessExitTimestampMs = now - 1_000L, nowMs = now))
        assertFalse(
            shouldArmStraySwitchCleanup(
                lastProcessExitTimestampMs = now - STRAY_SWITCH_RECENT_PROCESS_EXIT_WINDOW_MS,
                nowMs = now,
            ),
        )
        assertTrue(
            shouldArmStraySwitchCleanup(
                lastProcessExitTimestampMs = now - STRAY_SWITCH_RECENT_PROCESS_EXIT_WINDOW_MS - 1,
                nowMs = now,
            ),
        )
    }
}
