package dev.chirpboard.app.feature.widget

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Test

/** TST-014: start-path routing decisions (the stop paths live in WidgetReceiverStoppingTest). */
class WidgetReceiverStartRoutingTest {
    @Test
    fun widgetToggleActionFor_idle_returnsStart() {
        assertEquals(WidgetToggleAction.Start, widgetToggleActionFor(RecordingState.Idle))
    }

    @Test
    fun widgetToggleActionFor_error_returnsClearErrorAndStart() {
        val action =
            widgetToggleActionFor(
                RecordingState.Error(origin = RecordingOrigin.WIDGET, message = "mic busy"),
            )

        assertEquals(WidgetToggleAction.ClearErrorAndStart, action)
    }

    @Test
    fun widgetToggleActionFor_activeStates_returnStopActive() {
        listOf(
            RecordingState.Starting(origin = RecordingOrigin.APP),
            RecordingState.Recording(origin = RecordingOrigin.KEYBOARD),
            RecordingState.Paused(origin = RecordingOrigin.WIDGET),
        ).forEach { state ->
            assertEquals(WidgetToggleAction.StopActive, widgetToggleActionFor(state))
        }
    }
}
