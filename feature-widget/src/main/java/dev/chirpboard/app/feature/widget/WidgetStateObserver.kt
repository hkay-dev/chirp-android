package dev.chirpboard.app.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
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
    /** Test seam (TST-014): replaced with a TestScope so the collection loop is deterministic. */
    @VisibleForTesting
    internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var collectJob: Job? = null

    /**
     * Idempotent: a second call would otherwise leave two collectors rendering every
     * transition twice for the lifetime of the process.
     */
    fun startObserving() {
        if (collectJob?.isActive == true) {
            return
        }
        collectJob =
            scope.launch {
                // collect, not collectLatest: renderWidgets never suspends, so there is
                // nothing for the cancel-and-restart semantics to cancel.
                recordingStateManager.state.collect { state ->
                    renderWidgets(state)
                }
            }
    }

    private fun renderWidgets(state: RecordingState) {
        RecordingWidgetProvider.updateWidgetState(
            context,
            state,
            recordingStateManager.getCurrentDurationMs(),
        )
    }
}
