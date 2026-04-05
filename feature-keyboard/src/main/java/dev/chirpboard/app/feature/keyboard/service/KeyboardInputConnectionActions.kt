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
    if (delta == 0) return

    val keyEventCode = if (delta > 0) {
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT
    } else {
        android.view.KeyEvent.KEYCODE_DPAD_LEFT
    }

    val absDelta = kotlin.math.abs(delta)
    for (i in 0 until absDelta) {
        safeInputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyEventCode))
        safeInputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyEventCode))
    }
}
