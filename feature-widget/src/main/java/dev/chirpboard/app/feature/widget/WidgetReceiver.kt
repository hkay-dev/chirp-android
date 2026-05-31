package dev.chirpboard.app.feature.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.KeyboardRecordingStopBridge
import dev.chirpboard.app.core.recording.RecordingActiveStopCommands
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingServiceCommands
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

/**
 * BroadcastReceiver that handles widget button clicks.
 *
 * Toggles recording state based on current [RecordingStateManager] state:
 * - If idle: starts recording via [RecordingServiceCommands] with WIDGET origin
 * - If recording: stops the current recording using origin-aware routing
 */
@AndroidEntryPoint
class WidgetReceiver : BroadcastReceiver() {
    @Inject
    lateinit var recordingStateManager: RecordingStateManager

    @Inject
    lateinit var keyboardStopBridge: KeyboardRecordingStopBridge

    @Inject
    lateinit var pendingStopStore: KeyboardPendingStopStore

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            RecordingWidgetProvider.ACTION_TOGGLE_RECORDING -> {
                val pendingResult = goAsync()
                WidgetReceiverDispatch.dispatchToggle(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                    toggleRecording = { toggleRecording(context) },
                    finish = { pendingResult.finish() },
                )
            }
        }
    }

    internal suspend fun toggleRecording(context: Context) {
        when (widgetToggleActionFor(recordingStateManager.state.value)) {
            WidgetToggleAction.Start -> {
                RecordingServiceCommands.startRecording(
                    context = context,
                    origin = RecordingOrigin.WIDGET,
                    profileId = null,
                )
            }
            WidgetToggleAction.StopActive -> {
                RecordingActiveStopCommands.stopActiveRecording(
                    context = context,
                    recordingStateManager = recordingStateManager,
                    keyboardStopBridge = keyboardStopBridge,
                    pendingStopStore = pendingStopStore,
                    requesterOrigin = RecordingOrigin.WIDGET,
                    onKeyboardStopQueued = {
                        Toast.makeText(
                            context.applicationContext,
                            context.getString(R.string.widget_keyboard_stop_queued),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
            WidgetToggleAction.ShowStoppingFeedback -> {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.widget_finishing_recording),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            WidgetToggleAction.ClearErrorAndStart -> {
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

internal enum class WidgetToggleAction {
    Start,
    StopActive,
    ShowStoppingFeedback,
    ClearErrorAndStart,
}

internal fun widgetToggleActionFor(state: RecordingState): WidgetToggleAction =
    when (state) {
        is RecordingState.Idle -> WidgetToggleAction.Start
        is RecordingState.Recording,
        is RecordingState.Starting,
        is RecordingState.Paused,
        -> WidgetToggleAction.StopActive
        is RecordingState.Stopping -> WidgetToggleAction.ShowStoppingFeedback
        is RecordingState.Error -> WidgetToggleAction.ClearErrorAndStart
    }
