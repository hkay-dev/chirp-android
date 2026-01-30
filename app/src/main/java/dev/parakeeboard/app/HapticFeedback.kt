package dev.parakeeboard.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Provides haptic feedback for recording actions.
 * Uses VibrationEffect API (requires API 26+, which this app targets).
 */
object HapticFeedback {

    /**
     * Short click/tick feedback when recording starts.
     */
    fun onRecordStart(context: Context) {
        val vibrator = getVibrator(context) ?: return
        
        // Short click - 50ms at default amplitude
        val effect = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    /**
     * Double tick pattern when recording stops - distinct from start feedback.
     */
    fun onRecordStop(context: Context) {
        val vibrator = getVibrator(context) ?: return
        
        // Two quick pulses: vibrate 40ms, pause 60ms, vibrate 40ms
        // Pattern: [delay, vibrate, pause, vibrate]
        val timings = longArrayOf(0, 40, 60, 40)
        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
        
        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        vibrator.vibrate(effect)
    }

    private fun getVibrator(context: Context): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        
        // Check if vibrator is available and has vibration capability
        if (vibrator == null || !vibrator.hasVibrator()) {
            return null
        }
        
        return vibrator
    }
}
