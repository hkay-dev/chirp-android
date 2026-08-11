package dev.chirpboard.app.feature.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.KeyboardRecordingStopBridge
import dev.chirpboard.app.core.recording.RecordingActiveStopCommands
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.recording.RecordingServiceCommands
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                scope.launch {
                    try {
                        // The stop path can suspend on a DataStore write that is serialized
                        // behind another writer. goAsync only buys about ten seconds before
                        // the system reclaims the pending result and reports a receiver
                        // timeout against the process that also hosts the IME, so bound the
                        // work ourselves and tell the user rather than hanging until then.
                        withTimeout(TOGGLE_TIMEOUT_MS) { toggleRecording(context) }
                    } catch (e: TimeoutCancellationException) {
                        Log.e(TAG, "Widget toggle timed out", e)
                        Toast.makeText(
                            context.applicationContext,
                            context.getString(R.string.widget_action_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // This receiver runs in the process that hosts the IME; an uncaught
                        // exception here would kill the keyboard and any live recording.
                        Log.e(TAG, "Widget toggle failed", e)
                        Toast.makeText(
                            context.applicationContext,
                            context.getString(R.string.widget_action_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } finally {
                        pendingResult.finish()
                        // Every tap builds its own scope; without this each one leaves an
                        // Active Job behind for the lifetime of the process.
                        scope.cancel()
                    }
                }
            }
        }
    }

    internal suspend fun toggleRecording(context: Context) {
        when (widgetToggleActionFor(recordingStateManager.state.value)) {
            WidgetToggleAction.Start -> {
                startRecordingFromWidget(context)
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
                startRecordingFromWidget(context)
            }
        }
    }

    private fun startRecordingFromWidget(context: Context) {
        // A start without the mic permission can only bounce back as an Error state the
        // widget renders as a bare "Error"; say what is actually wrong instead.
        if (!RecordingPermissionGuard.hasRecordAudioPermission(context)) {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.widget_permission_needed),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val dispatched =
            RecordingServiceCommands.startRecording(
                context = context,
                origin = RecordingOrigin.WIDGET,
                profileId = null,
            )
        if (dispatched) {
            // Optimistic frame: real state stays Idle until the service's onStartCommand
            // runs, so without this the widget shows "Tap to record" for the whole
            // service-start latency and an impatient second tap stops the recording the
            // first tap just started.
            RecordingWidgetProvider.updateWidgetState(
                context,
                RecordingState.Starting(RecordingOrigin.WIDGET),
                currentDurationMs = 0L,
            )
        } else {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.widget_start_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private companion object {
        const val TAG = "WidgetReceiver"

        /** Comfortably inside the platform's foreground broadcast budget. */
        const val TOGGLE_TIMEOUT_MS = 8_000L
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
