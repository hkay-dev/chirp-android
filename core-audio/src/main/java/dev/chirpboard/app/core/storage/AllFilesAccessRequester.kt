package dev.chirpboard.app.core.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log

object AllFilesAccessRequester {
    private const val TAG = "AllFilesAccess"

    fun needsPermission(): Boolean = !Environment.isExternalStorageManager()

    /**
     * Opens the most specific All-Files-Access settings surface available, falling back when
     * an OEM build does not resolve the per-app intent (ERR-22 — the old single-intent version
     * silently no-oped, leaving the Download button dead):
     * 1. the per-app "Allow all files access" toggle page;
     * 2. the global All-Files-Access app list;
     * 3. the app-details settings page (Permissions -> Files is reachable from there).
     *
     * @return true when some settings surface was opened; false when every fallback failed
     * (callers should surface manual instructions, e.g. record_entry_model_storage_denied_message).
     */
    fun openSettings(context: Context): Boolean {
        if (!needsPermission()) {
            return true
        }

        val packageUri = Uri.parse("package:${context.packageName}")
        val candidates =
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).setData(packageUri),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri),
            )

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No activity for ${intent.action}; trying fallback", e)
            } catch (e: SecurityException) {
                // Some OEM settings activities resolve but refuse the launch; treat the same
                // as not-found so the next fallback still runs instead of crashing the tap.
                Log.w(TAG, "Launch refused for ${intent.action}; trying fallback", e)
            }
        }
        Log.e(TAG, "No settings surface available for All Files Access")
        return false
    }
}
