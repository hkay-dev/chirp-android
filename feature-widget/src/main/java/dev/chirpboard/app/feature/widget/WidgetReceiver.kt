package dev.chirpboard.app.feature.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingServiceCommands
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import javax.inject.Inject

/**
 * BroadcastReceiver that handles widget button clicks.
 *
 * Toggles recording state based on current [RecordingStateManager] state:
 * - If idle: starts recording via [RecordingServiceCommands] with WIDGET origin
 * - If recording: stops the current recording
 */
@AndroidEntryPoint
class WidgetReceiver : BroadcastReceiver() {
    @Inject
    lateinit var recordingStateManager: RecordingStateManager

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            RecordingWidgetProvider.ACTION_TOGGLE_RECORDING -> {
                toggleRecording(context)
            }
        }
    }

    private fun toggleRecording(context: Context) {
        when (recordingStateManager.state.value) {
            is RecordingState.Idle -> {
                RecordingServiceCommands.startRecording(
                    context = context,
                    origin = RecordingOrigin.WIDGET,
                    profileId = null,
                )
            }
            is RecordingState.Recording,
            is RecordingState.Starting,
            is RecordingState.Paused,
            -> {
                RecordingServiceCommands.stopRecording(context)
            }
            is RecordingState.Stopping -> Unit
            is RecordingState.Error -> {
                recordingStateManager.clearError()
                RecordingServiceCommands.startRecording(
                    context = context,
                    origin = RecordingOrigin.WIDGET,
                    profileId = null,
                )
            }
        }
    }
}
