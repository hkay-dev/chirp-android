package dev.chirpboard.app.feature.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager

/**
 * WorkManager worker to update widget UI based on current recording state.
 * 
 * This worker is enqueued when recording state changes to ensure the widget
 * reflects the current state even if the app process was killed.
 */
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val recordingStateManager: RecordingStateManager
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val currentState = recordingStateManager.state.value
        
        val isRecording = currentState is RecordingState.Recording || 
                          currentState is RecordingState.Starting
        
        RecordingWidgetProvider.updateWidget(context, isRecording, null)
        
        return Result.success()
    }
    
    
    companion object {
        private const val UNIQUE_WORK_NAME = "widget_update"
        
        /**
         * Enqueue a widget update work request.
         * Replaces any existing pending update to avoid duplicate work.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
