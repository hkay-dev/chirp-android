package dev.chirpboard.app.feature.recording.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.feature.recording.R

/**
 * Microphone-permission affordances shared by the record entry points (ERR-7).
 *
 * The startup prompt is one-shot; once the user denies (or revokes) RECORD_AUDIO, the record
 * surfaces must offer a real re-request path and — after Android's permanent denial — a deep link
 * into the app's system settings page instead of a dead-end snackbar.
 */
internal fun isMicPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * True when a fresh denial should be treated as permanent (the system dialog will no longer be
 * shown), so the only remaining path is the app settings page.
 */
internal fun isMicPermissionPermanentlyDenied(context: Context): Boolean {
    val activity = context.findActivity() ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.RECORD_AUDIO,
    )
}

internal fun openAppSettingsForPermission(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

internal fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()

/**
 * "Microphone access needed" dialog with a deep link into the app's settings page, shown after a
 * permanent denial (ERR-7).
 */
@Composable
internal fun MicPermissionSettingsDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rec_mic_permission_needed_title)) },
        text = { Text(stringResource(R.string.rec_mic_permission_needed_message)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.rec_mic_permission_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreR.string.rec_cancel))
            }
        },
    )
}
