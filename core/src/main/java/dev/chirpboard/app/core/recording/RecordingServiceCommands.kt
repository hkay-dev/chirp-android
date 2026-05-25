package dev.chirpboard.app.core.recording

import android.content.Context
import android.content.Intent
import java.util.UUID

/**
 * Intent commands for the recording foreground service.
 *
 * Feature modules that need to start or stop recording should use this API instead of
 * referencing [dev.chirpboard.app.feature.recording.service.RecordingService] directly.
 */
object RecordingServiceCommands {
    const val SERVICE_CLASS_NAME = "dev.chirpboard.app.feature.recording.service.RecordingService"

    const val ACTION_START_RECORDING = "dev.chirpboard.app.ACTION_START_RECORDING"
    const val ACTION_PAUSE_RECORDING = "dev.chirpboard.app.ACTION_PAUSE_RECORDING"
    const val ACTION_RESUME_RECORDING = "dev.chirpboard.app.ACTION_RESUME_RECORDING"
    const val ACTION_STOP_RECORDING = "dev.chirpboard.app.ACTION_STOP_RECORDING"
    const val ACTION_CANCEL_RECORDING = "dev.chirpboard.app.ACTION_CANCEL_RECORDING"
    const val ACTION_RESTART_RECORDING = "dev.chirpboard.app.ACTION_RESTART_RECORDING"
    const val EXTRA_ORIGIN = "extra_origin"
    const val EXTRA_PROFILE_ID = "extra_profile_id"

    fun startRecording(
        context: Context,
        origin: RecordingOrigin = RecordingOrigin.APP,
        profileId: UUID? = null,
    ) {
        val intent =
            serviceIntent(context, ACTION_START_RECORDING).apply {
                putExtra(EXTRA_ORIGIN, origin.name)
                profileId?.let { putExtra(EXTRA_PROFILE_ID, it.toString()) }
            }
        context.startForegroundService(intent)
    }

    fun pauseRecording(context: Context) {
        context.startService(serviceIntent(context, ACTION_PAUSE_RECORDING))
    }

    fun resumeRecording(context: Context) {
        context.startService(serviceIntent(context, ACTION_RESUME_RECORDING))
    }

    fun stopRecording(context: Context) {
        context.startService(serviceIntent(context, ACTION_STOP_RECORDING))
    }

    fun cancelRecording(context: Context) {
        context.startService(serviceIntent(context, ACTION_CANCEL_RECORDING))
    }

    fun restartRecording(
        context: Context,
        origin: RecordingOrigin = RecordingOrigin.APP,
        profileId: UUID? = null,
    ) {
        val intent =
            serviceIntent(context, ACTION_RESTART_RECORDING).apply {
                putExtra(EXTRA_ORIGIN, origin.name)
                profileId?.let { putExtra(EXTRA_PROFILE_ID, it.toString()) }
            }
        context.startService(intent)
    }

    private fun serviceIntent(
        context: Context,
        action: String,
    ): Intent =
        Intent().apply {
            setClassName(context.packageName, SERVICE_CLASS_NAME)
            this.action = action
        }
}
