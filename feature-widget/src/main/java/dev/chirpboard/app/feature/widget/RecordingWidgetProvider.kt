package dev.chirpboard.app.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.feature.widget.R

/**
 * AppWidgetProvider for the recording widget.
 *
 * Displays a record/stop button based on current recording state.
 * Widget UI is updated via [updateWidgetState] when recording state changes, and
 * [onUpdate] re-renders the REAL current state (IME-16/PLT-04) whenever the launcher
 * re-inflates widgets (widget add, launcher restart, theme/Good Lock changes) — a
 * hardcoded Idle frame here used to show "Tap to record" while the mic was live,
 * and tapping it stopped the recording the UI claimed was not running.
 */
class RecordingWidgetProvider : AppWidgetProvider() {
    /** Hilt access from the static widget callback (the receiver runs in the app process). */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface WidgetEntryPoint {
        fun recordingStateManager(): RecordingStateManager
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val stateManager =
            runCatching {
                EntryPointAccessors
                    .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                    .recordingStateManager()
            }.onFailure { Log.w(TAG, "Recording state unavailable; rendering Idle", it) }
                .getOrNull()
        val state = stateManager?.state?.value ?: RecordingState.Idle
        val durationMs = stateManager?.getCurrentDurationMs() ?: 0L
        updateAppWidgetWithState(context, appWidgetManager, appWidgetIds, state, durationMs)
    }

    companion object {
        private const val TAG = "RecordingWidget"
        const val ACTION_TOGGLE_RECORDING = "dev.chirpboard.app.TOGGLE_RECORDING"

        fun updateWidgetState(context: Context, state: RecordingState, currentDurationMs: Long) {
            // getInstance is null on devices with no widget host (rare, but real).
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, RecordingWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) {
                return
            }
            updateAppWidgetWithState(context, appWidgetManager, appWidgetIds, state, currentDurationMs)
        }

        /**
         * Every placed widget renders the same frame, so the RemoteViews — and with it the
         * PackageManager launch-intent query and the two PendingIntent lookups, none of which
         * vary by widget id — is built once and pushed to all of them in one call.
         */
        internal fun updateAppWidgetWithState(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            state: RecordingState,
            currentDurationMs: Long
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Button glyph + tint + accessible description follow the ACTION a tap performs
            // (IME-21: Paused taps stop-and-save, so Paused shows the stop glyph and says so).
            val buttonSpec = widgetButtonSpecFor(state)
            views.setImageViewResource(R.id.widget_button, buttonSpec.iconRes)
            views.setInt(R.id.widget_button, "setColorFilter", context.getColor(buttonSpec.tintRes))
            views.setContentDescription(
                R.id.widget_button,
                context.getString(buttonSpec.contentDescriptionRes),
            )

            // The ticking Chronometer is only for live recording. Status words go through a
            // plain TextView: setChronometer's third argument is a FORMAT string, so routing
            // user-facing copy through it meant any translation with a stray '%' would
            // silently render as a raw since-boot elapsed-time counter.
            val statusTextRes = widgetStatusTextRes(state)
            if (statusTextRes == null) {
                views.setViewVisibility(R.id.widget_status, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.widget_status_text, android.view.View.GONE)
                val base = android.os.SystemClock.elapsedRealtime() - currentDurationMs
                views.setChronometer(R.id.widget_status, base, null, true)
            } else {
                views.setViewVisibility(R.id.widget_status, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_status_text, android.view.View.VISIBLE)
                views.setChronometer(R.id.widget_status, 0, null, false)
                views.setTextViewText(R.id.widget_status_text, context.getString(statusTextRes))
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

            // Everywhere that is not the button opens the app, so the widget has a
            // non-destructive tap target (it previously had none).
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context,
                        0,
                        launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }

            // Update the widgets
            appWidgetManager.updateAppWidget(appWidgetIds, views)
        }
    }
}

/** How the widget's single button should render for a recording state. */
internal data class WidgetButtonSpec(
    @field:DrawableRes val iconRes: Int,
    @field:ColorRes val tintRes: Int,
    @field:StringRes val contentDescriptionRes: Int,
)

/**
 * Pure state -> status line mapping (JVM-testable). Null means "show the ticking
 * chronometer instead". Paused gets an explicit word: a frozen timer alone was
 * indistinguishable from Stopping apart from the button tint.
 */
internal fun widgetStatusTextRes(state: RecordingState): Int? =
    when (state) {
        is RecordingState.Recording -> null
        is RecordingState.Paused -> R.string.widget_status_paused
        is RecordingState.Starting -> R.string.widget_status_starting
        is RecordingState.Stopping -> R.string.widget_status_saving
        is RecordingState.Error -> R.string.widget_status_error
        is RecordingState.Idle -> R.string.widget_status_idle
    }

/**
 * Pure state -> render mapping (JVM-testable, TST-014). Derived from
 * [widgetToggleActionFor] so the glyph/description always advertise the action a tap
 * actually performs (IME-21).
 */
internal fun widgetButtonSpecFor(state: RecordingState): WidgetButtonSpec =
    when (widgetToggleActionFor(state)) {
        WidgetToggleAction.Start,
        WidgetToggleAction.ClearErrorAndStart,
        ->
            WidgetButtonSpec(
                iconRes = R.drawable.ic_widget_record,
                tintRes = R.color.widget_tint_live,
                contentDescriptionRes = R.string.widget_desc_start_recording,
            )
        WidgetToggleAction.StopActive ->
            WidgetButtonSpec(
                iconRes = R.drawable.ic_widget_stop,
                tintRes = R.color.widget_tint_live,
                contentDescriptionRes = R.string.widget_desc_stop_recording,
            )
        WidgetToggleAction.ShowStoppingFeedback ->
            WidgetButtonSpec(
                iconRes = R.drawable.ic_widget_stop,
                tintRes = R.color.widget_tint_saving,
                contentDescriptionRes = R.string.widget_desc_saving_recording,
            )
    }
