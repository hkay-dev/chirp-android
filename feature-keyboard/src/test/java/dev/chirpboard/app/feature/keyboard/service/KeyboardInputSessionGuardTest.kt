package dev.chirpboard.app.feature.keyboard.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardInputSessionGuardTest {
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
    fun `no personalized learning input cannot capture commit session`() {
        val guard = KeyboardInputSessionGuard()

        guard.startInput(
            EditorInfo().apply {
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
        assertNull(guard.captureCommitSession())
    }

    @Test
    fun `current input session commits text`() {
        val guard = KeyboardInputSessionGuard()
        val connection = mockk<InputConnection>()
        every { connection.commitText("hello", 1) } returns true
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        assertTrue(guard.commitIfCurrent(session, connection, "hello"))

        verify { connection.commitText("hello", 1) }
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

        assertFalse(guard.commitIfCurrent(session, connection, "late"))
        verify(exactly = 0) { connection.commitText(any(), any()) }
    }

    @Test
    fun `preserved session survives config change restart`() {
        val guard = KeyboardInputSessionGuard()
        val connection = mockk<InputConnection>()
        every { connection.commitText("hello", 1) } returns true
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(EditorInfo(), preserveSession = true)

        assertTrue(guard.commitIfCurrent(session, connection, "hello"))
        verify { connection.commitText("hello", 1) }
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

        assertFalse(guard.commitIfCurrent(session, connection, "late"))
        verify(exactly = 0) { connection.commitText(any(), any()) }
    }

    @Test
    fun `preserved session is not carried through null editor info`() {
        val guard = KeyboardInputSessionGuard()
        val connection = mockk<InputConnection>(relaxed = true)
        guard.startInput(EditorInfo())
        val session = requireNotNull(guard.captureCommitSession())

        guard.startInput(null, preserveSession = true)

        assertFalse(guard.commitIfCurrent(session, connection, "late"))
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
        val connection = mockk<InputConnection>()
        every { connection.commitText("hello", 1) } returns true
        // Registered before any input session exists, exactly like the service wires
        // it in onCreate. A provider that captured the session here would be stuck
        // with no (or a stale) session for every later limit stop.
        val provider =
            guard.commitTextProvider { session, text ->
                guard.commitIfCurrent(session, connection, text)
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
        val connection = mockk<InputConnection>()
        every { connection.commitText(any(), 1) } returns true
        val provider =
            guard.commitTextProvider { session, text ->
                guard.commitIfCurrent(session, connection, text)
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
}
