package dev.chirpboard.app.feature.keyboard.service

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * What the keyboard's action key should look like and do for the current editor (IME-1).
 *
 * Derived from [EditorInfo.imeOptions] so a chat field gets Send, a search box gets Search and a
 * form gets Next/Done — while multiline editors, `IME_FLAG_NO_ENTER_ACTION` editors and editors
 * without an action keep a plain Enter that inserts a newline.
 */
enum class KeyboardImeActionKind {
    ENTER,
    DONE,
    SEARCH,
    SEND,
    NEXT,
    GO,
    PREVIOUS,
}

data class KeyboardImeAction(
    val kind: KeyboardImeActionKind,
    /**
     * The id passed to [InputConnection.performEditorAction]; null for plain Enter. Honors a
     * custom [EditorInfo.actionId] when the editor supplies one.
     */
    val performActionId: Int? = null,
) {
    companion object {
        val Enter = KeyboardImeAction(KeyboardImeActionKind.ENTER)
    }
}

internal fun resolveImeAction(info: EditorInfo?): KeyboardImeAction {
    if (info == null) return KeyboardImeAction.Enter
    if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return KeyboardImeAction.Enter
    val isMultiLineText =
        (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
    if (isMultiLineText) return KeyboardImeAction.Enter

    val maskedAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
    val kind =
        when (maskedAction) {
            EditorInfo.IME_ACTION_DONE -> KeyboardImeActionKind.DONE
            EditorInfo.IME_ACTION_SEARCH -> KeyboardImeActionKind.SEARCH
            EditorInfo.IME_ACTION_SEND -> KeyboardImeActionKind.SEND
            EditorInfo.IME_ACTION_NEXT -> KeyboardImeActionKind.NEXT
            EditorInfo.IME_ACTION_GO -> KeyboardImeActionKind.GO
            EditorInfo.IME_ACTION_PREVIOUS -> KeyboardImeActionKind.PREVIOUS
            else -> return KeyboardImeAction.Enter
        }
    val performActionId = if (info.actionId != 0) info.actionId else maskedAction
    return KeyboardImeAction(kind = kind, performActionId = performActionId)
}

/**
 * Performs [action] against the editor: editor actions go through
 * [InputConnection.performEditorAction]; plain Enter (and any refused action) falls back to an
 * Enter key event, which multiline editors turn into a newline.
 */
internal fun performImeAction(
    inputConnection: InputConnection?,
    action: KeyboardImeAction,
) {
    val connection = inputConnection ?: return
    val performActionId = action.performActionId
    if (action.kind == KeyboardImeActionKind.ENTER || performActionId == null) {
        sendEnterKeyEvent(connection)
        return
    }
    if (!connection.performEditorAction(performActionId)) {
        sendEnterKeyEvent(connection)
    }
}

private fun sendEnterKeyEvent(connection: InputConnection) {
    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
}
