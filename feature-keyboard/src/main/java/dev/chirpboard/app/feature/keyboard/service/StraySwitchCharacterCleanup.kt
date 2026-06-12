package dev.chirpboard.app.feature.keyboard.service

import android.view.inputmethod.InputConnection

/**
 * Window after IME-service creation inside which the stray-switch cleanup may run (IME-5).
 *
 * A genuine input-method switch recreates this service, so the SwiftKey stray-letter artifact can
 * only exist within moments of `onCreate`. Outside this window the keyboard is binding a new
 * client for ordinary reasons (app switch, refocus) and a trailing standalone z/Z is legitimate
 * user text ("gen z", "…plan Z") that must never be deleted.
 */
internal const val STRAY_SWITCH_CLEANUP_FRESHNESS_MS = 3_000L

/**
 * Whether a client bind may still be attributed to an input-method switch (IME-5). Only the
 * FIRST bind after service creation qualifies, and only within the freshness window.
 */
internal fun shouldAttemptStraySwitchCleanup(
    uptimeSinceServiceCreateMs: Long,
    freshnessWindowMs: Long = STRAY_SWITCH_CLEANUP_FRESHNESS_MS,
): Boolean = uptimeSinceServiceCreateMs in 0..freshnessWindowMs

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
