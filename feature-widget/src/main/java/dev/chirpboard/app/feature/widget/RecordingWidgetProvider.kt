package dev.chirpboard.app.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.feature.widget.R

/**
 * AppWidgetProvider for the recording widget.
 * 
 * Displays a record/stop button based on current recording state.
 * Widget UI is updated via [updateWidgetState] when recording state changes.
 */
class RecordingWidgetProvider : AppWidgetProvider() {
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidgetWithState(context, appWidgetManager, appWidgetId, RecordingState.Idle, 0L)
        }
    }
    
    
    companion object {
        const val ACTION_TOGGLE_RECORDING = "dev.chirpboard.app.TOGGLE_RECORDING"
        
        fun updateWidgetState(context: Context, state: RecordingState, currentDurationMs: Long) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, RecordingWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            for (appWidgetId in appWidgetIds) {
                updateAppWidgetWithState(context, appWidgetManager, appWidgetId, state, currentDurationMs)
            }
        }

        fun updateAppWidgetWithState(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: RecordingState,
            currentDurationMs: Long
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            
            when (state) {
                is RecordingState.Recording -> {
                    views.setImageViewResource(R.id.widget_button, R.drawable.ic_widget_stop)
                    views.setInt(R.id.widget_button, "setColorFilter", 0xFFE53935.toInt()) // Red tint
                    // Use Chronometer for recording duration
                    val base = android.os.SystemClock.elapsedRealtime() - currentDurationMs
                    views.setChronometer(R.id.widget_status, base, null, true)
                }
                is RecordingState.Paused -> {
                    views.setImageViewResource(R.id.widget_button, R.drawable.ic_widget_record)
                    views.setInt(R.id.widget_button, "setColorFilter", 0xFFE53935.toInt()) // Red tint
                    // Show the accumulated time statically
                    val base = android.os.SystemClock.elapsedRealtime() - currentDurationMs
                    views.setChronometer(R.id.widget_status, base, null, false)
                }
                is RecordingState.Starting -> {
                    views.setImageViewResource(R.id.widget_button, R.drawable.ic_widget_stop)
                    views.setInt(R.id.widget_button, "setColorFilter", 0xFFE53935.toInt())
                    views.setChronometer(R.id.widget_status, 0, "Starting...", false)
                }
                is RecordingState.Stopping -> {
                    views.setImageViewResource(R.id.widget_button, R.drawable.ic_widget_stop)
                    views.setInt(R.id.widget_button, "setColorFilter", 0xFF9E9E9E.toInt()) // Grey tint
                    views.setChronometer(R.id.widget_status, 0, "Saving...", false)
                }
                is RecordingState.Error -> {
                    views.setImageViewResource(R.id.widget_button, R.drawable.ic_widget_record)
                    views.setInt(R.id.widget_button, "setColorFilter", 0xFFE53935.toInt())
                    views.setChronometer(R.id.widget_status, 0, "Error", false)
                }
                is RecordingState.Idle -> {
                    views.setImageViewResource(R.id.widget_button, R.drawable.ic_widget_record)
                    views.setInt(R.id.widget_button, "setColorFilter", 0xFFE53935.toInt())
                    views.setChronometer(R.id.widget_status, 0, "Tap to record", false)
                }
            }
            
            // Set up click handler for toggle button
            val toggleIntent = Intent(context, WidgetReceiver::class.java).apply {
                action = ACTION_TOGGLE_RECORDING
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_button, pendingIntent)
            
            // Update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
