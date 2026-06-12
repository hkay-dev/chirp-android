package dev.chirpboard.app.feature.keyboard.haptic

import android.content.Context
import dev.chirpboard.app.core.ui.haptics.ChirpHaptics

/**
 * Keyboard haptic feedback.
 *
 * Thin facade over the shared [ChirpHaptics] (core-ui) so the keyboard and the rest of the app
 * share one tactile language (PRM-1). The keyboard's specific call sites and effects are preserved
 * exactly; only the implementation now lives in core-ui.
 */
object HapticFeedback {

    /** Short click/tick feedback when recording starts. */
    fun onRecordStart(context: Context) = ChirpHaptics.recordStart(context)

    /** Double tick pattern when recording stops - distinct from start feedback. */
    fun onRecordStop(context: Context) = ChirpHaptics.recordStop(context)

    /** Distinct pulse when backspace switches to word-delete mode. */
    fun onBackspaceWordMode(context: Context) = ChirpHaptics.escalate(context)

    /** Light tick when backspace is pressed. */
    fun onBackspace(context: Context) = ChirpHaptics.tap(context)

    /** Very subtle tick while sliding the spacebar to move the cursor. */
    fun onCursorStep(context: Context) = ChirpHaptics.cursorStep(context)
}
