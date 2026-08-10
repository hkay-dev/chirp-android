package dev.chirpboard.app.feature.transcription

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast

private const val TAG = "QuickInputCopy"

/**
 * Invisible focus-holding surface for the quick-input notification's copy actions.
 *
 * Android's clipboard policy only guarantees a write for the focused app or the default
 * IME. The previous BroadcastReceiver ran with the app in the background, so on builds
 * enforcing that policy the write was silently dropped while the receiver's "Copied" toast
 * still showed. Copying from a briefly focused translucent activity makes the write land
 * everywhere, and on Android 13+ the system's own clipboard confirmation replaces the toast.
 */
class QuickInputCopyActivity : Activity() {
    private var copyAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If window focus never arrives (another window steals it immediately), this
        // invisible activity must not linger: attempt the copy anyway and leave.
        window.decorView.postDelayed(::copyAndFinish, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) copyAndFinish()
    }

    private fun copyAndFinish() {
        if (copyAttempted || isFinishing) return
        copyAttempted = true
        performCopy(intent)
        finish()
    }

    private fun performCopy(intent: Intent?) {
        val copyAi = intent?.action == ACTION_COPY_AI
        if (!copyAi && intent?.action != ACTION_COPY_RAW) return
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Toast.makeText(this, R.string.quick_input_result_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val clip = ClipData.newPlainText(getString(R.string.transcription_title), text)
        clip.description.extras =
            PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        try {
            clipboard.setPrimaryClip(clip)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not copy quick-input result", error)
            Toast.makeText(this, R.string.quick_input_result_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // Android 13+ shows the system clipboard confirmation; a toast would duplicate it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                this,
                if (copyAi) R.string.quick_input_result_copied_ai else R.string.quick_input_result_copied_raw,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        internal const val ACTION_COPY_RAW = "dev.chirpboard.app.action.COPY_QUICK_INPUT_RAW"
        internal const val ACTION_COPY_AI = "dev.chirpboard.app.action.COPY_QUICK_INPUT_AI"
        internal const val EXTRA_TEXT = "dev.chirpboard.app.extra.QUICK_INPUT_TEXT"
        private const val FOCUS_TIMEOUT_MS = 1_500L
    }
}
