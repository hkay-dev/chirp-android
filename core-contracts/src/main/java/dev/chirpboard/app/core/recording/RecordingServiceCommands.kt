package dev.chirpboard.app.core.recording

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID

/**
 * Intent commands for the recording foreground service.
 *
 * Feature modules that need to start or stop recording should use this API instead of
 * referencing [dev.chirpboard.app.feature.recording.service.RecordingService] directly.
 *
 * All dispatch helpers return false instead of throwing when the system refuses the
 * command (e.g. background start restrictions), so a failed dispatch can never crash
 * the calling process — which the recording service shares with the keyboard IME.
 */
object RecordingServiceCommands {
    private const val TAG = "RecordingServiceCommands"

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
    ): Boolean {
        val intent =
            serviceIntent(context, ACTION_START_RECORDING).apply {
                putExtra(EXTRA_ORIGIN, origin.name)
                profileId?.let { putExtra(EXTRA_PROFILE_ID, it.toString()) }
            }
        return dispatch(context, intent, foreground = true)
    }

    fun pauseRecording(context: Context): Boolean =
        dispatch(context, serviceIntent(context, ACTION_PAUSE_RECORDING), foreground = false)

    fun resumeRecording(context: Context): Boolean =
        dispatch(context, serviceIntent(context, ACTION_RESUME_RECORDING), foreground = false)

    fun stopRecording(context: Context): Boolean =
        dispatch(context, serviceIntent(context, ACTION_STOP_RECORDING), foreground = false)

    fun cancelRecording(context: Context): Boolean =
        dispatch(context, serviceIntent(context, ACTION_CANCEL_RECORDING), foreground = false)

    fun restartRecording(
        context: Context,
        origin: RecordingOrigin = RecordingOrigin.APP,
        profileId: UUID? = null,
    ): Boolean {
        val intent =
            serviceIntent(context, ACTION_RESTART_RECORDING).apply {
                putExtra(EXTRA_ORIGIN, origin.name)
                profileId?.let { putExtra(EXTRA_PROFILE_ID, it.toString()) }
            }
        return dispatch(context, intent, foreground = false)
    }

    private fun dispatch(
        context: Context,
        intent: Intent,
        foreground: Boolean,
    ): Boolean =
        guardDispatch(intent.action) {
            if (foreground) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

    internal fun guardDispatch(
        action: String?,
        start: () -> Unit,
    ): Boolean =
        try {
            start()
            true
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException and BackgroundServiceStartNotAllowedException.
            Log.e(TAG, "Recording service command $action rejected by the system", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Recording service command $action rejected by the system", e)
            false
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
