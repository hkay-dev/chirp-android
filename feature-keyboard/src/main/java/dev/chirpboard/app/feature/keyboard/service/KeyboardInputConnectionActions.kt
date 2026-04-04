package dev.chirpboard.app.feature.keyboard.service

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

internal fun deletePreviousCharacter(inputConnection: InputConnection?) {
    inputConnection?.deleteSurroundingText(1, 0)
}

internal fun commitSpace(inputConnection: InputConnection?) {
    inputConnection?.commitText(" ", 1)
}

internal fun moveCursor(inputConnection: InputConnection?, delta: Int) {
    val safeInputConnection = inputConnection ?: return
    val extractedText = safeInputConnection.getExtractedText(ExtractedTextRequest(), 0) ?: return
    val currentPos = extractedText.selectionStart
    val textLength = extractedText.text.length
    val newPos = (currentPos + delta).coerceIn(0, textLength)
    safeInputConnection.setSelection(newPos, newPos)
}
