package dev.chirpboard.app.feature.widget

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

@Singleton
class WidgetStateObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingStateManager: RecordingStateManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun startObserving() {
        scope.launch {
            recordingStateManager.state.collectLatest { state ->
                RecordingWidgetProvider.updateWidgetState(context, state, recordingStateManager.getCurrentDurationMs())
            }
        }
    }
}
