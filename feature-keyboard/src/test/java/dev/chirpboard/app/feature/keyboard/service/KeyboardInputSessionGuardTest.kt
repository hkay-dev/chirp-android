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
}
