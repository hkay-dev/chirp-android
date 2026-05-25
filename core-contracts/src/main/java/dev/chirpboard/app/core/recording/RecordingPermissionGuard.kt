package dev.chirpboard.app.core.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object RecordingPermissionGuard {
    const val PERMISSION_DENIED_MESSAGE = "Microphone access is required."

    fun hasRecordAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
