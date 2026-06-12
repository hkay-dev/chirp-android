package dev.chirpboard.app.core.ui.haptics

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Reusable haptic-feedback utility, usable from any module (PRM-1).
 *
 * Promoted out of the keyboard so the in-app record screen, the home Record FAB, the recognition
 * dialog and destructive confirmations can share one consistent tactile language. All effects are
 * no-ops when the device has no vibrator. Effect choices mirror the keyboard's originals so the two
 * halves of the product feel identical:
 *  - [recordStart]: a single short click.
 *  - [recordStop]: a distinct double-tick.
 *  - [tap]: a light predefined click (taps, list-row play).
 *  - [delete]: a heavy click for destructive confirms.
 *  - [success]: a double-click for completion (e.g. transcription finished).
 *  - [cursorStep]: a very subtle tick (spacebar cursor drag).
 *  - [escalate]: a heavy click for mode escalation (e.g. backspace word-delete).
 */
object ChirpHaptics {

    /** Short click/tick when recording starts (50ms one-shot). */
    fun recordStart(context: Context) {
        val vibrator = getVibrator(context) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(RECORD_START_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Double-tick when recording stops — distinct from [recordStart]. */
    fun recordStop(context: Context) {
        val vibrator = getVibrator(context) ?: return
        // Two quick pulses: [delay, vibrate, pause, vibrate].
        val timings = longArrayOf(0, 40, 60, 40)
        val amplitudes =
            intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, NO_REPEAT))
    }

    /** Light tick for a generic confirming tap (play, primary tap). */
    fun tap(context: Context) {
        vibratePredefined(context, VibrationEffect.EFFECT_CLICK)
    }

    /** Heavy thunk for a destructive confirm (delete). */
    fun delete(context: Context) {
        vibratePredefined(context, VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    /** Completion feedback (e.g. transcription finished). */
    fun success(context: Context) {
        vibratePredefined(context, VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    /** Very subtle tick while sliding the spacebar to move the cursor. */
    fun cursorStep(context: Context) {
        vibratePredefined(context, VibrationEffect.EFFECT_TICK)
    }

    /** Distinct pulse when an interaction escalates (e.g. backspace → word-delete mode). */
    fun escalate(context: Context) {
        vibratePredefined(context, VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    private fun vibratePredefined(context: Context, effectId: Int) {
        val vibrator = getVibrator(context) ?: return
        vibrator.vibrate(VibrationEffect.createPredefined(effectId))
    }

    private fun getVibrator(context: Context): Vibrator? {
        val vibratorManager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        val vibrator = vibratorManager?.defaultVibrator
        if (vibrator == null || !vibrator.hasVibrator()) {
            return null
        }
        return vibrator
    }

    private const val RECORD_START_MS = 50L
    private const val NO_REPEAT = -1
}
