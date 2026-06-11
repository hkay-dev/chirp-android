package dev.chirpboard.app.feature.keyboard.service

import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

internal fun deletePreviousCharacter(inputConnection: InputConnection?) {
    val connection = inputConnection ?: return

    val selected = connection.getSelectedText(0)
    connection.finishComposingText()
    if (selected != null && selected.isNotEmpty()) {
        connection.commitText("", 1)
        return
    }

    val before = connection.getTextBeforeCursor(2, 0)
    if (before.isNullOrEmpty()) {
        sendDeleteKeyEvent(connection)
        return
    }

    val deleteCount = codeUnitDeleteCount(before)
    if (!connection.deleteSurroundingText(deleteCount, 0)) {
        sendDeleteKeyEvent(connection)
    }
}

internal fun deletePreviousWord(inputConnection: InputConnection?) {
    val connection = inputConnection ?: return

    val selected = connection.getSelectedText(0)

    connection.finishComposingText()
    if (selected != null && selected.isNotEmpty()) {
        connection.commitText("", 1)
        return
    }

    val before = connection.getTextBeforeCursor(512, 0)
    if (before.isNullOrEmpty()) {
        sendDeleteKeyEvent(connection)
        return
    }

    val deleteCount = previousWordDeleteCount(before)
    if (deleteCount <= 0) {
        deletePreviousCharacter(connection)
        return
    }

    if (!connection.deleteSurroundingText(deleteCount, 0)) {
        repeat(deleteCount.coerceAtMost(32)) {
            deletePreviousCharacter(connection)
        }
    }
}

private fun previousWordDeleteCount(before: CharSequence): Int {
    var index = before.length
    while (index > 0 && before[index - 1].isWhitespace()) {
        index--
    }
    if (index == 0) {
        return before.length
    }

    if (before[index - 1].isLetterOrDigit()) {
        while (index > 0 && before[index - 1].isLetterOrDigit()) {
            index--
        }
    } else {
        while (index > 0 && !before[index - 1].isWhitespace() && !before[index - 1].isLetterOrDigit()) {
            index--
        }
    }
    return before.length - index
}

private fun codeUnitDeleteCount(before: CharSequence): Int {
    if (before.isEmpty()) return 0

    return if (before.length >= 2 && Character.isSurrogatePair(before[before.length - 2], before[before.length - 1])) {
        2
    } else {
        1
    }
}

internal fun commitSpace(inputConnection: InputConnection?) {
    val connection = inputConnection ?: return
    connection.finishComposingText()
    connection.commitText(" ", 1)
}

internal fun moveCursor(inputConnection: InputConnection?, delta: Int) {
    val connection = inputConnection ?: return
    if (delta == 0) return
    connection.finishComposingText()

    val extracted =
        connection.getExtractedText(
            ExtractedTextRequest().apply {
                flags = InputConnection.GET_EXTRACTED_TEXT_MONITOR
            },
            0,
        )
    val text = extracted?.text
    if (text != null) {
        val textLength = text.length
        var current = extracted.selectionStart
        var selectionEnd = extracted.selectionEnd

        if (current !in 0..textLength || selectionEnd !in 0..textLength) {
            current = current.coerceIn(0, textLength)
            connection.setSelection(current, current)
            selectionEnd = current
        } else if (current != selectionEnd) {
            val collapsed = if (delta > 0) maxOf(current, selectionEnd) else minOf(current, selectionEnd)
            connection.setSelection(collapsed, collapsed)
            return
        }

        val newPos = moveCursorByCodePoints(text, current, delta)
        if (newPos != current) {
            connection.setSelection(newPos, newPos)
        }
        return
    }

    moveCursorWithKeyEvents(connection, delta)
}

private fun moveCursorByCodePoints(
    text: CharSequence,
    current: Int,
    delta: Int,
): Int {
    var index = current.coerceIn(0, text.length)
    repeat(kotlin.math.abs(delta)) {
        val next =
            if (delta > 0) {
                nextCodePointBoundary(text, index)
            } else {
                previousCodePointBoundary(text, index)
            }
        if (next == index) {
            return index
        }
        index = next
    }
    return index
}

private fun nextCodePointBoundary(
    text: CharSequence,
    index: Int,
): Int {
    if (index >= text.length) return text.length
    return if (
        index + 1 < text.length &&
        Character.isHighSurrogate(text[index]) &&
        Character.isLowSurrogate(text[index + 1])
    ) {
        index + 2
    } else {
        index + 1
    }
}

private fun previousCodePointBoundary(
    text: CharSequence,
    index: Int,
): Int {
    if (index <= 0) return 0
    return if (
        index >= 2 &&
        Character.isLowSurrogate(text[index - 1]) &&
        Character.isHighSurrogate(text[index - 2])
    ) {
        index - 2
    } else {
        index - 1
    }
}

private fun moveCursorWithKeyEvents(connection: InputConnection, delta: Int) {
    val keyEventCode =
        if (delta > 0) {
            KeyEvent.KEYCODE_DPAD_RIGHT
        } else {
            KeyEvent.KEYCODE_DPAD_LEFT
        }

    repeat(kotlin.math.abs(delta)) {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyEventCode))
    }
}

private fun sendDeleteKeyEvent(connection: InputConnection) {
    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
}
