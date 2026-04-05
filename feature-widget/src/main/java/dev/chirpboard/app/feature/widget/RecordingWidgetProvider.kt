package dev.chirpboard.app.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.chirpboard.app.feature.widget.R

/**
 * AppWidgetProvider for the recording widget.
 * 
 * Displays a record/stop button based on current recording state.
 * Widget UI is updated via [updateWidget] when recording state changes.
 */
class RecordingWidgetProvider : AppWidgetProvider() {
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Update each widget instance
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, isRecording = false)
        }
    }
    
    
    companion object {
        const val ACTION_TOGGLE_RECORDING = "dev.chirpboard.app.TOGGLE_RECORDING"
        
        /**
         * Update all widget instances with the current recording state.
         * 
         * @param context Application context
         * @param isRecording Whether recording is currently active
         * @param durationText Optional duration text to display (e.g., "1:23")
         */
        fun updateWidget(context: Context, isRecording: Boolean, durationText: String? = null) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, RecordingWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, isRecording, durationText)
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            isRecording: Boolean,
            durationText: String? = null
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            
            // Set button icon based on state
            if (isRecording) {
                views.setImageViewResource(R.id.widget_button, R.drawable.ic_widget_stop)
                views.setInt(R.id.widget_button, "setColorFilter", 0xFFE53935.toInt()) // Red tint
                // Use Chronometer for recording duration
                views.setChronometer(R.id.widget_status, android.os.SystemClock.elapsedRealtime(), null, true)
            } else {
                views.setImageViewResource(R.id.widget_button, R.drawable.ic_widget_record)
                views.setInt(R.id.widget_button, "setColorFilter", 0xFFE53935.toInt()) // Red tint
                // Stop chronometer and show default text
                views.setChronometer(R.id.widget_status, 0, "Tap to record", false)
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
