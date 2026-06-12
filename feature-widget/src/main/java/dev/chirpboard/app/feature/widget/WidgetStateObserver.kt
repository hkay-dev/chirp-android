package dev.chirpboard.app.feature.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes recording-state transitions into the home-screen widget.
 *
 * PRF-10: collection runs on [Dispatchers.Default], not Main — every emission performs
 * AppWidgetManager binder round-trips (and, with widgets placed, RemoteViews builds), which
 * previously executed on the main thread at every process start (the state flow emits its
 * initial Idle immediately) and on every recording state transition. When no widgets are
 * placed the per-emission work stops at the id lookup.
 */
@Singleton
class WidgetStateObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingStateManager: RecordingStateManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun startObserving() {
        scope.launch {
            recordingStateManager.state.collectLatest { state ->
                renderWidgets(state)
            }
        }
    }

    private fun renderWidgets(state: RecordingState) {
        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
        val widgetIds =
            appWidgetManager.getAppWidgetIds(
                ComponentName(context, RecordingWidgetProvider::class.java),
            )
        if (widgetIds == null || widgetIds.isEmpty()) return

        val durationMs = recordingStateManager.getCurrentDurationMs()
        for (widgetId in widgetIds) {
            RecordingWidgetProvider.updateAppWidgetWithState(
                context,
                appWidgetManager,
                widgetId,
                state,
                durationMs,
            )
        }
    }
}
