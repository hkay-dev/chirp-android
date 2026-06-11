package dev.chirpboard.app.feature.keyboard.service

import android.view.inputmethod.InputConnection

/**
 * Some keyboards place their voice key next to a letter key (SwiftKey's mic sits by Z), so
 * invoking this keyboard from there sometimes commits a stray letter into the editor right
 * before the IME switch. When this keyboard takes over a freshly bound client, a lone z/Z
 * immediately before the cursor is treated as that stray press and removed.
 */
internal fun removeStraySwitchCharacter(connection: InputConnection?): Boolean {
    val target = connection ?: return false
    if (!target.getSelectedText(0).isNullOrEmpty()) {
        return false
    }
    val before = target.getTextBeforeCursor(2, 0) ?: return false
    if (!endsWithStandaloneStrayCharacter(before)) {
        return false
    }
    return target.deleteSurroundingText(1, 0)
}

internal fun endsWithStandaloneStrayCharacter(before: CharSequence): Boolean {
    val last = before.lastOrNull() ?: return false
    if (last != 'z' && last != 'Z') {
        return false
    }
    if (before.length < 2) {
        return true
    }
    return !before[before.length - 2].isLetterOrDigit()
}
