package dev.chirpboard.app.feature.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.feature.recording.service.RecordingService
import javax.inject.Inject

/**
 * BroadcastReceiver that handles widget button clicks.
 * 
 * Toggles recording state based on current [RecordingStateManager] state:
 * - If idle: starts recording via [RecordingService] with WIDGET origin
 * - If recording: stops the current recording
 */
@AndroidEntryPoint
class WidgetReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var recordingStateManager: RecordingStateManager
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RecordingWidgetProvider.ACTION_TOGGLE_RECORDING -> {
                toggleRecording(context)
            }
        }
    }
    
    private fun toggleRecording(context: Context) {
        val currentState = recordingStateManager.state.value
        
        when (currentState) {
            is RecordingState.Idle -> {
                // Start recording from widget
                RecordingService.startRecording(
                    context = context,
                    origin = RecordingOrigin.WIDGET,
                    profileId = null
                )
            }
            is RecordingState.Recording,
            is RecordingState.Starting -> {
                // Stop current recording
                RecordingService.stopRecording(context)
            }
            is RecordingState.Stopping -> {
                // Already stopping, do nothing
            }
            is RecordingState.Error -> {
                // Clear error and allow new recording
                recordingStateManager.clearError()
                RecordingService.startRecording(
                    context = context,
                    origin = RecordingOrigin.WIDGET,
                    profileId = null
                )
            }
        }
    }
}
