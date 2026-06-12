package dev.chirpboard.app.feature.keyboard.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardImeActionTest {
    @Test
    fun `null editor info resolves to enter`() {
        assertEquals(KeyboardImeAction.Enter, resolveImeAction(null))
    }

    @Test
    fun `editor actions resolve to their kinds`() {
        assertEquals(
            KeyboardImeActionKind.DONE,
            resolveImeAction(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_DONE }).kind,
        )
        assertEquals(
            KeyboardImeActionKind.SEARCH,
            resolveImeAction(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }).kind,
        )
        assertEquals(
            KeyboardImeActionKind.SEND,
            resolveImeAction(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEND }).kind,
        )
        assertEquals(
            KeyboardImeActionKind.NEXT,
            resolveImeAction(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_NEXT }).kind,
        )
        assertEquals(
            KeyboardImeActionKind.GO,
            resolveImeAction(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_GO }).kind,
        )
        assertEquals(
            KeyboardImeActionKind.PREVIOUS,
            resolveImeAction(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_PREVIOUS }).kind,
        )
    }

    @Test
    fun `action id defaults to the masked ime action`() {
        val action = resolveImeAction(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEND })
        assertEquals(EditorInfo.IME_ACTION_SEND, action.performActionId)
    }

    @Test
    fun `custom actionId is honored over the masked action`() {
        val action =
            resolveImeAction(
                EditorInfo().apply {
                    imeOptions = EditorInfo.IME_ACTION_SEND
                    actionId = 99
                },
            )
        assertEquals(KeyboardImeActionKind.SEND, action.kind)
        assertEquals(99, action.performActionId)
    }

    @Test
    fun `no enter action flag falls back to enter`() {
        val action =
            resolveImeAction(
                EditorInfo().apply {
                    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
                },
            )
        assertEquals(KeyboardImeAction.Enter, action)
    }

    @Test
    fun `multiline text editor falls back to enter`() {
        val action =
            resolveImeAction(
                EditorInfo().apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = EditorInfo.IME_ACTION_DONE
                },
            )
        assertEquals(KeyboardImeAction.Enter, action)
    }

    @Test
    fun `unspecified and none actions fall back to enter`() {
        assertEquals(
            KeyboardImeAction.Enter,
            resolveImeAction(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED }),
        )
        assertEquals(
            KeyboardImeAction.Enter,
            resolveImeAction(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_NONE }),
        )
    }

    @Test
    fun `perform sends editor action`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.performEditorAction(EditorInfo.IME_ACTION_SEND) } returns true

        performImeAction(connection, KeyboardImeAction(KeyboardImeActionKind.SEND, EditorInfo.IME_ACTION_SEND))

        verify { connection.performEditorAction(EditorInfo.IME_ACTION_SEND) }
        verify(exactly = 0) { connection.sendKeyEvent(any()) }
    }

    @Test
    fun `perform enter sends enter key events`() {
        val connection = mockk<InputConnection>(relaxed = true)

        performImeAction(connection, KeyboardImeAction.Enter)

        verify(exactly = 2) { connection.sendKeyEvent(any()) }
        verify(exactly = 0) { connection.performEditorAction(any()) }
    }

    @Test
    fun `refused editor action falls back to enter key events`() {
        val connection = mockk<InputConnection>(relaxed = true)
        every { connection.performEditorAction(any()) } returns false

        performImeAction(connection, KeyboardImeAction(KeyboardImeActionKind.DONE, EditorInfo.IME_ACTION_DONE))

        verify(exactly = 2) { connection.sendKeyEvent(any()) }
    }

    @Test
    fun `null connection is a no-op`() {
        performImeAction(null, KeyboardImeAction.Enter)
    }
}
