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
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        when (intent.action) {
            ACTION_UPDATE_WIDGET -> {
                val isRecording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
                val durationText = intent.getStringExtra(EXTRA_DURATION_TEXT)
                updateAllWidgets(context, isRecording, durationText)
            }
        }
    }
    
    private fun updateAllWidgets(context: Context, isRecording: Boolean, durationText: String?) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, RecordingWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, isRecording, durationText)
        }
    }
    
    private fun updateAppWidget(
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
            views.setTextViewText(R.id.widget_status, durationText ?: "Recording...")
        } else {
            views.setImageViewResource(R.id.widget_button, R.drawable.ic_widget_record)
            views.setInt(R.id.widget_button, "setColorFilter", 0xFFE53935.toInt()) // Red tint
            views.setTextViewText(R.id.widget_status, "Tap to record")
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
    
    companion object {
        const val ACTION_TOGGLE_RECORDING = "dev.chirpboard.app.TOGGLE_RECORDING"
        const val ACTION_UPDATE_WIDGET = "dev.chirpboard.app.UPDATE_WIDGET"
        const val EXTRA_IS_RECORDING = "extra_is_recording"
        const val EXTRA_DURATION_TEXT = "extra_duration_text"
        
        /**
         * Update all widget instances with the current recording state.
         * 
         * @param context Application context
         * @param isRecording Whether recording is currently active
         * @param durationText Optional duration text to display (e.g., "1:23")
         */
        fun updateWidget(context: Context, isRecording: Boolean, durationText: String? = null) {
            val intent = Intent(context, RecordingWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
                putExtra(EXTRA_IS_RECORDING, isRecording)
                durationText?.let { putExtra(EXTRA_DURATION_TEXT, it) }
            }
            context.sendBroadcast(intent)
        }
    }
}
