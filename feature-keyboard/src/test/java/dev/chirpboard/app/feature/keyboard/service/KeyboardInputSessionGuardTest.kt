package dev.chirpboard.app.feature.keyboard.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardInputSessionGuardTest {
    private fun commitConnection(): InputConnection =
        mockk<InputConnection>(relaxed = true).also { connection ->
            // The post-commit verification readback (any request longer than the 1-char spacing
            // probes) reports "cannot provide context", which the guard accepts as committed.
            every { connection.getTextBeforeCursor(more(1), 0) } returns null
            every { connection.getTextBeforeCursor(1, 0) } returns ""
            every { connection.getTextAfterCursor(1, 0) } returns ""
            every { connection.commitText(any(), 1) } returns true
        }

    @Test
    fun `session cannot be captured before input starts`() {
        val guard = KeyboardInputSessionGuard()

        assertNull(guard.captureCommitSession())
    }

    @Test
    fun `password text input cannot capture commit session`() {
        val guard = KeyboardInputSessionGuard()

        guard.startInput(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
        )

        assertTrue(guard.isSensitiveInput)
        assertNull(guard.captureCommitSession())
    }

    @Test
    fun `no personalized learning input keeps dictation but suppresses learning`() {
        // IME-3 (intentional behavior change): Chrome/Firefox set this flag on EVERY incognito
        // field — the keyboard must stay fully functional there; only history persistence is
        // suppressed. The old behavior (treat as sensitive, brick the keyboard) was wrong.
        val guard = KeyboardInputSessionGuard()

        guard.startInput(
            EditorInfo().apply {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            },
        )

        assertFalse(guard.isSensitiveInput)
        assertTrue(guard.isLearningSuppressed)
        assertNotNull(guard.captureCommitSession())
    }

    @Test
    fun `password input with no learning flag stays blocked`() {
        val guard = KeyboardInputSessionGuard()

        guard.startInput(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            },
        )

        assertTrue(guard.isSensitiveInput)
        assertNull(guard.captureCommitSession())
    }

    @Test
    fun `null editor info cannot capture commit session`() {
        val guard = KeyboardInputSessionGuard()

        guard.startInput(null)

        assertTrue(guard.isSensitiveInput)
        assertFalse(guard.isLearningSuppressed)
        assertNull(guard.captureCommitSession())
    }

    @Test
    fun `learning suppression clears when leaving the incognito field`() {
        val guard = KeyboardInputSessionGuard()
        guard.startInput(
            EditorInfo().apply {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            },
        )
        assertTrue(guard.isLearningSuppressed)

        guard.startInput(EditorInfo())

        assertFalse(guard.isLearningSuppressed)
    }

    @Test
    fun `current input session commits text`() {
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        assertTrue(guard.commitIfCurrent(session, connection, "hello").committed)

        verify { connection.commitText("hello", 1) }
    }

    @Test
    fun `commit clears composing region inside one batch edit`() {
        // IME-12/IME-23: a composing span left by a previous IME must not be replaced by the
        // dictation commit, and the whole fix-up is one atomic editor change.
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        assertTrue(guard.commitIfCurrent(session, connection, "hello").committed)

        verify { connection.beginBatchEdit() }
        verify { connection.finishComposingText() }
        verify { connection.endBatchEdit() }
    }

    @Test
    fun `commit inserts leading space after a word`() {
        // IME-14: dictating at "Hello|" must not glue into "Helloworld ".
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        every { connection.getTextBeforeCursor(1, 0) } returns "o"
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        assertTrue(guard.commitIfCurrent(session, connection, "world ").committed)

        verify { connection.commitText(" world ", 1) }
    }

    @Test
    fun `commit drops trailing space before punctuation`() {
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        every { connection.getTextBeforeCursor(1, 0) } returns " "
        every { connection.getTextAfterCursor(1, 0) } returns "."
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        assertTrue(guard.commitIfCurrent(session, connection, "world ").committed)

        verify { connection.commitText("world", 1) }
    }

    @Test
    fun `finished input cannot capture commit session`() {
        val guard = KeyboardInputSessionGuard()
        guard.startInput(EditorInfo())

        guard.finishInput()

        assertNull(guard.captureCommitSession())
    }

    @Test
    fun `stale input session refuses late text`() {
        val guard = KeyboardInputSessionGuard()
        val connection = mockk<InputConnection>(relaxed = true)
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(EditorInfo())

        assertFalse(guard.commitIfCurrent(session, connection, "late").committed)
        verify(exactly = 0) { connection.commitText(any(), any()) }
    }

    @Test
    fun `preserved session survives config change restart`() {
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(EditorInfo(), preserveSession = true)

        assertTrue(guard.commitIfCurrent(session, connection, "hello").committed)
        verify { connection.commitText("hello", 1) }
    }

    @Test
    fun `same editor restart preserves session`() {
        // IME-11: EditText.setText/autofill triggers restartInput on the SAME field; a transcript
        // finishing inside that window can still safely commit there.
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        val editor =
            EditorInfo().apply {
                fieldId = 42
                packageName = "com.example.chat"
                inputType = InputType.TYPE_CLASS_TEXT
            }
        guard.startInput(editor)
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(
            EditorInfo().apply {
                fieldId = 42
                packageName = "com.example.chat"
                inputType = InputType.TYPE_CLASS_TEXT
            },
            restarting = true,
        )

        assertTrue(guard.commitIfCurrent(session, connection, "hello").committed)
        verify { connection.commitText("hello", 1) }
    }

    @Test
    fun `restart for a different editor invalidates the session`() {
        val guard = KeyboardInputSessionGuard()
        val connection = mockk<InputConnection>(relaxed = true)
        guard.startInput(
            EditorInfo().apply {
                fieldId = 42
                packageName = "com.example.chat"
            },
        )
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(
            EditorInfo().apply {
                fieldId = 7
                packageName = "com.example.chat"
            },
            restarting = true,
        )

        assertFalse(guard.commitIfCurrent(session, connection, "late").committed)
        verify(exactly = 0) { connection.commitText(any(), any()) }
    }

    @Test
    fun `non-restart same editor still invalidates the session`() {
        // Only restartInput is provably the same editor; a fresh startInput means focus moved.
        val guard = KeyboardInputSessionGuard()
        val connection = mockk<InputConnection>(relaxed = true)
        val editor = EditorInfo().apply { fieldId = 42 }
        guard.startInput(editor)
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(EditorInfo().apply { fieldId = 42 })

        assertFalse(guard.commitIfCurrent(session, connection, "late").committed)
    }

    @Test
    fun `same editor restart never preserves into a password field`() {
        val guard = KeyboardInputSessionGuard()
        val connection = mockk<InputConnection>(relaxed = true)
        val editor = EditorInfo().apply { fieldId = 42 }
        guard.startInput(editor)
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(
            EditorInfo().apply {
                fieldId = 42
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
            restarting = true,
        )

        assertFalse(guard.commitIfCurrent(session, connection, "late").committed)
        verify(exactly = 0) { connection.commitText(any(), any()) }
    }

    @Test
    fun `preserved session is not carried into sensitive input`() {
        val guard = KeyboardInputSessionGuard()
        val connection = mockk<InputConnection>(relaxed = true)
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
            preserveSession = true,
        )

        assertFalse(guard.commitIfCurrent(session, connection, "late").committed)
        verify(exactly = 0) { connection.commitText(any(), any()) }
    }

    @Test
    fun `preserved session is not carried through null editor info`() {
        val guard = KeyboardInputSessionGuard()
        val connection = mockk<InputConnection>(relaxed = true)
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(null, preserveSession = true)

        assertFalse(guard.commitIfCurrent(session, connection, "late").committed)
        verify(exactly = 0) { connection.commitText(any(), any()) }
    }

    @Test
    fun `normal input can capture commit session`() {
        val guard = KeyboardInputSessionGuard()

        guard.startInput(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            },
        )

        assertFalse(guard.isSensitiveInput)
        assertNotNull(guard.captureCommitSession())
    }

    @Test
    fun `commit text provider captures session at stop time not at registration`() {
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        // Registered before any input session exists, exactly like the service wires
        // it in onCreate. A provider that captured the session here would be stuck
        // with no (or a stale) session for every later limit stop.
        val provider =
            guard.commitTextProvider { session, text ->
                guard.commitIfCurrent(session, connection, text).committed
            }

        assertNull(provider())

        guard.startInput(EditorInfo())
        val commit = requireNotNull(provider())
        assertTrue(commit("hello"))
        verify { connection.commitText("hello", 1) }
    }

    @Test
    fun `commit text provider binds each stop to the live session`() {
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        val provider =
            guard.commitTextProvider { session, text ->
                guard.commitIfCurrent(session, connection, text).committed
            }
        guard.startInput(EditorInfo())
        val staleCommit = requireNotNull(provider())

        guard.startInput(EditorInfo())

        // A commit captured before the field changed refuses its late text...
        assertFalse(staleCommit("late"))
        verify(exactly = 0) { connection.commitText("late", 1) }
        // ...while invoking the provider again binds to the new live session.
        val liveCommit = requireNotNull(provider())
        assertTrue(liveCommit("hello"))
        verify { connection.commitText("hello", 1) }
    }

    @Test
    fun `verified readback commits exactly once`() {
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        every { connection.getTextBeforeCursor(more(1), 0) } returns "hello "
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        assertEquals(
            KeyboardDictationCommitResult.COMMITTED,
            guard.commitIfCurrent(session, connection, "hello "),
        )
        verify(exactly = 1) { connection.commitText("hello ", 1) }
    }

    @Test
    fun `commit retries once when the readback proves the text missing`() {
        // RELY-1: the editor claimed success but the field stayed empty; one retry lands it.
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        every { connection.getTextBeforeCursor(more(1), 0) } returnsMany listOf("", "hello ")
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        assertEquals(
            KeyboardDictationCommitResult.COMMITTED_AFTER_RETRY,
            guard.commitIfCurrent(session, connection, "hello "),
        )
        verify(exactly = 2) { connection.commitText("hello ", 1) }
    }

    @Test
    fun `commit reports verification failure when the retry never lands either`() {
        val guard = KeyboardInputSessionGuard()
        val connection = commitConnection()
        every { connection.getTextBeforeCursor(more(1), 0) } returns ""
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        val result = guard.commitIfCurrent(session, connection, "hello ")

        assertEquals(KeyboardDictationCommitResult.VERIFICATION_FAILED, result)
        assertFalse(result.committed)
        verify(exactly = 2) { connection.commitText("hello ", 1) }
    }

    @Test
    fun `verifyDictationCommitReadback classifies readbacks conservatively`() {
        // Exact tail present: verified, even with the pipeline's trailing space trimmed.
        assertEquals(
            DictationCommitVerification.VERIFIED,
            verifyDictationCommitReadback(readback = "Say hello ", committed = "hello "),
        )
        assertEquals(
            DictationCommitVerification.VERIFIED,
            verifyDictationCommitReadback(readback = "Say hello", committed = "hello "),
        )
        // Blank commits have nothing to verify.
        assertEquals(
            DictationCommitVerification.VERIFIED,
            verifyDictationCommitReadback(readback = "", committed = " "),
        )
        // Null readback: the editor cannot expose context, nothing is provable.
        assertEquals(
            DictationCommitVerification.UNVERIFIABLE,
            verifyDictationCommitReadback(readback = null, committed = "hello "),
        )
        // Truncated context that matches the committed tail is presence, not absence.
        assertEquals(
            DictationCommitVerification.UNVERIFIABLE,
            verifyDictationCommitReadback(readback = "lo world", committed = "hello world "),
        )
        // A transformed commit (editor added punctuation) still shows the probe tail.
        assertEquals(
            DictationCommitVerification.UNVERIFIABLE,
            verifyDictationCommitReadback(readback = "hello world!", committed = "hello world "),
        )
        // An empty or unrelated field proves the commit was dropped.
        assertEquals(
            DictationCommitVerification.MISSING,
            verifyDictationCommitReadback(readback = "", committed = "hello "),
        )
        assertEquals(
            DictationCommitVerification.MISSING,
            verifyDictationCommitReadback(readback = "something else", committed = "hello world "),
        )
    }

    @Test
    fun `resolveDictationCommitText covers spacing decisions`() {
        // No surrounding context: unchanged.
        assertEquals("hello ", resolveDictationCommitText(before = "", after = "", text = "hello "))
        assertEquals("hello ", resolveDictationCommitText(before = null, after = null, text = "hello "))
        // After a word: leading space inserted.
        assertEquals(" hello ", resolveDictationCommitText(before = "o", after = "", text = "hello "))
        // After whitespace or an opener: no leading space.
        assertEquals("hello ", resolveDictationCommitText(before = " ", after = "", text = "hello "))
        assertEquals("hello ", resolveDictationCommitText(before = "(", after = "", text = "hello "))
        assertEquals("hello ", resolveDictationCommitText(before = "\n", after = "", text = "hello "))
        // Before whitespace or closing punctuation: trailing space dropped.
        assertEquals(" hello", resolveDictationCommitText(before = "o", after = " ", text = "hello "))
        assertEquals("hello", resolveDictationCommitText(before = " ", after = ".", text = "hello "))
        assertEquals("hello", resolveDictationCommitText(before = null, after = ")", text = "hello "))
        // Empty text passes through untouched.
        assertEquals("", resolveDictationCommitText(before = "o", after = ".", text = ""))
    }
}
