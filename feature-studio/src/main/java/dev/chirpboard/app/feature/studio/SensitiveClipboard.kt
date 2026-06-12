package dev.chirpboard.app.feature.studio

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copies transcript-derived [text] to the clipboard with
 * [ClipDescription.EXTRA_IS_SENSITIVE] set, so the system clipboard preview overlay
 * and clipboard editor hide dictated content instead of echoing it on screen.
 *
 * Compose's `ClipboardManager.setText` cannot set the sensitive flag, which is why
 * these copies go through the platform [ClipboardManager] directly (SEC-7).
 */
internal fun copySensitiveTextToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip =
        ClipData.newPlainText(label, text).apply {
            description.extras =
                PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
        }
    clipboard.setPrimaryClip(clip)
}
