package dev.chirpboard.app.feature.keyboard.service

import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * Context window for grapheme-aware backspace (IME-8). Long ZWJ clusters (a four-person family
 * emoji is 11 UTF-16 units) need more than the old 2-unit surrogate window; 64 units comfortably
 * covers every real cluster.
 */
private const val GRAPHEME_CONTEXT_UNITS = 64

/** Bound the one-shot extract copy for cursor movement (IME-18) instead of copying whole documents. */
private const val EXTRACT_HINT_MAX_CHARS = 10_000

/** Cap for the single-character fallback loop when the editor refuses deleteSurroundingText. */
private const val WORD_DELETE_FALLBACK_CAP = 32

internal fun deletePreviousCharacter(inputConnection: InputConnection?) {
    val connection = inputConnection ?: return

    val selected = connection.getSelectedText(0)
    connection.finishComposingText()
    if (selected != null && selected.isNotEmpty()) {
        connection.commitText("", 1)
        return
    }

    val before = connection.getTextBeforeCursor(GRAPHEME_CONTEXT_UNITS, 0)
    if (before.isNullOrEmpty()) {
        sendDeleteKeyEvent(connection)
        return
    }

    // IME-8: delete the whole trailing grapheme cluster (flags, ZWJ families, skin tones, VS16
    // hearts, combining marks) instead of a single code point.
    val deleteCount = GraphemeBoundaries.trailingClusterLength(before)
    if (deleteCount <= 0 || !connection.deleteSurroundingText(deleteCount, 0)) {
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
        // IME-23: one batch edit around the per-character fallback so the editor sees a single
        // invalidation instead of up to 32 flickering intermediate states.
        connection.beginBatchEdit()
        try {
            repeat(deleteCount.coerceAtMost(WORD_DELETE_FALLBACK_CAP)) {
                deletePreviousCharacter(connection)
            }
        } finally {
            connection.endBatchEdit()
        }
    }
}

/**
 * Code-point-based word scan (IME-8): combining marks count as part of the word (so NFD "café"
 * deletes wholly instead of just its accent) and supplementary-plane letters are classified by
 * code point rather than by surrogate halves.
 */
private fun previousWordDeleteCount(before: CharSequence): Int {
    var index = before.length
    while (index > 0) {
        val cp = Character.codePointBefore(before, index)
        if (!Character.isWhitespace(cp)) break
        index -= Character.charCount(cp)
    }
    if (index == 0) {
        return before.length
    }

    if (isWordCodePoint(Character.codePointBefore(before, index))) {
        while (index > 0) {
            val cp = Character.codePointBefore(before, index)
            if (!isWordCodePoint(cp)) break
            index -= Character.charCount(cp)
        }
    } else {
        while (index > 0) {
            val cp = Character.codePointBefore(before, index)
            if (Character.isWhitespace(cp) || isWordCodePoint(cp)) break
            index -= Character.charCount(cp)
        }
    }
    return before.length - index
}

private fun isWordCodePoint(cp: Int): Boolean {
    if (Character.isLetterOrDigit(cp)) return true
    return when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
        -> true

        else -> false
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
    connection.beginBatchEdit()
    try {
        connection.finishComposingText()

        // IME-18: one-shot read — never GET_EXTRACTED_TEXT_MONITOR (which subscribes this IME to
        // every later text change), and bound the copied window for huge documents.
        val extracted =
            connection.getExtractedText(
                ExtractedTextRequest().apply {
                    flags = 0
                    hintMaxChars = EXTRACT_HINT_MAX_CHARS
                },
                0,
            )
        val text = extracted?.text
        // IME-17: selectionStart/End of -1 means "no selection reported" — fall back to key
        // events instead of coercing the cursor to the top of the document.
        if (text == null || extracted.selectionStart < 0 || extracted.selectionEnd < 0) {
            moveCursorWithKeyEvents(connection, delta)
            return
        }

        // IME-17: extract offsets are relative to ExtractedText.text, which starts at startOffset
        // within the document; convert before every setSelection.
        val startOffset = extracted.startOffset.coerceAtLeast(0)
        val textLength = text.length
        var current = extracted.selectionStart
        var selectionEnd = extracted.selectionEnd

        if (current > textLength || selectionEnd > textLength) {
            current = current.coerceIn(0, textLength)
            connection.setSelection(startOffset + current, startOffset + current)
            selectionEnd = current
        } else if (current != selectionEnd) {
            val collapsed = if (delta > 0) maxOf(current, selectionEnd) else minOf(current, selectionEnd)
            connection.setSelection(startOffset + collapsed, startOffset + collapsed)
            return
        }

        val newPos = moveCursorByGraphemes(text, current, delta)
        if (newPos != current) {
            connection.setSelection(startOffset + newPos, startOffset + newPos)
        }
    } finally {
        connection.endBatchEdit()
    }
}

/** IME-8: cursor steps move by grapheme cluster so the caret never parks inside a flag/ZWJ glyph. */
private fun moveCursorByGraphemes(
    text: CharSequence,
    current: Int,
    delta: Int,
): Int {
    var index = current.coerceIn(0, text.length)
    repeat(kotlin.math.abs(delta)) {
        val next =
            if (delta > 0) {
                GraphemeBoundaries.nextBoundary(text, index)
            } else {
                GraphemeBoundaries.previousBoundary(text, index)
            }
        if (next == index) {
            return index
        }
        index = next
    }
    return index
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
