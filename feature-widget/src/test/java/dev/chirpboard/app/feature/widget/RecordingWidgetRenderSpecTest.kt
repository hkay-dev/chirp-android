package dev.chirpboard.app.feature.widget

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TST-014 / IME-16 / IME-21: the widget button's glyph + accessible description must always
 * advertise the action a tap performs (routing decided by [widgetToggleActionFor]).
 */
class RecordingWidgetRenderSpecTest {
    @Test
    fun idle_rendersRecordGlyphWithStartDescription() {
        val spec = widgetButtonSpecFor(RecordingState.Idle)

        assertEquals(R.drawable.ic_widget_record, spec.iconRes)
        assertEquals(R.string.widget_desc_start_recording, spec.contentDescriptionRes)
    }

    @Test
    fun recording_rendersStopGlyphWithStopDescription() {
        val spec =
            widgetButtonSpecFor(
                RecordingState.Recording(origin = RecordingOrigin.WIDGET),
            )

        assertEquals(R.drawable.ic_widget_stop, spec.iconRes)
        assertEquals(R.string.widget_desc_stop_recording, spec.contentDescriptionRes)
    }

    @Test
    fun starting_rendersStopGlyphWithStopDescription() {
        val spec =
            widgetButtonSpecFor(
                RecordingState.Starting(origin = RecordingOrigin.APP),
            )

        assertEquals(R.drawable.ic_widget_stop, spec.iconRes)
        assertEquals(R.string.widget_desc_stop_recording, spec.contentDescriptionRes)
    }

    @Test
    fun paused_rendersStopGlyphBecauseTapStopsAndSaves() {
        // IME-21 regression: Paused used to show the record glyph while a tap actually
        // stopped the recording.
        val spec =
            widgetButtonSpecFor(
                RecordingState.Paused(origin = RecordingOrigin.APP, accumulatedMs = 1_000L),
            )

        assertEquals(R.drawable.ic_widget_stop, spec.iconRes)
        assertEquals(R.string.widget_desc_stop_recording, spec.contentDescriptionRes)
    }

    @Test
    fun stopping_rendersSavingDescription() {
        val spec =
            widgetButtonSpecFor(
                RecordingState.Stopping(
                    origin = RecordingOrigin.APP,
                    recordingId = UUID.randomUUID(),
                ),
            )

        assertEquals(R.drawable.ic_widget_stop, spec.iconRes)
        assertEquals(R.string.widget_desc_saving_recording, spec.contentDescriptionRes)
    }

    @Test
    fun error_rendersRecordGlyphWithStartDescription() {
        val spec =
            widgetButtonSpecFor(
                RecordingState.Error(origin = RecordingOrigin.WIDGET, message = "boom"),
            )

        assertEquals(R.drawable.ic_widget_record, spec.iconRes)
        assertEquals(R.string.widget_desc_start_recording, spec.contentDescriptionRes)
    }
}
