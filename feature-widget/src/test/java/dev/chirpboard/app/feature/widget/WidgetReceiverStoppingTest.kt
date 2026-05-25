package dev.chirpboard.app.feature.widget

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetReceiverStoppingTest {
    @Test
    fun widgetToggleActionFor_stopping_returnsShowStoppingFeedback() {
        val action =
            widgetToggleActionFor(
                RecordingState.Stopping(
                    origin = RecordingOrigin.APP,
                    recordingId = UUID.randomUUID(),
                ),
            )

        assertEquals(WidgetToggleAction.ShowStoppingFeedback, action)
    }

    @Test
    fun widgetToggleActionFor_stopping_doesNotRequestStopOrStart() {
        val action =
            widgetToggleActionFor(
                RecordingState.Stopping(
                    origin = RecordingOrigin.WIDGET,
                    recordingId = UUID.randomUUID(),
                ),
            )

        assertEquals(WidgetToggleAction.ShowStoppingFeedback, action)
    }
}
