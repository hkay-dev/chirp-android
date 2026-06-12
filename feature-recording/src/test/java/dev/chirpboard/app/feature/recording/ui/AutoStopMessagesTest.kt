package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.service.RecordingAutoStopReason
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ERR-13/ERR-14: the in-app auto-stop snackbar must say exactly what the system
 * notification says — every reason maps to the same string resource, and the snackbar
 * wraps it in the shared "stopped and saved" template.
 */
class AutoStopMessagesTest {
    @Test
    fun `every auto-stop reason maps to its notification string`() {
        assertEquals(R.string.rec_auto_stop_storage, RecordingAutoStopReason.STORAGE_CRITICAL.reasonStringRes())
        assertEquals(R.string.rec_auto_stop_focus, RecordingAutoStopReason.FOCUS_LOST.reasonStringRes())
        assertEquals(R.string.rec_auto_stop_device, RecordingAutoStopReason.INPUT_DEVICE_LOST.reasonStringRes())
        assertEquals(R.string.rec_auto_stop_capture_error, RecordingAutoStopReason.CAPTURE_ERROR.reasonStringRes())
    }

    @Test
    fun `snackbar message embeds the reason in the stopped-and-saved template`() {
        val context =
            mockk<Context> {
                every { getString(R.string.rec_auto_stop_storage) } returns "storage was almost full"
                every { getString(R.string.rec_auto_stop_snackbar, "storage was almost full") } returns
                    "Recording stopped and saved — storage was almost full"
            }

        assertEquals(
            "Recording stopped and saved — storage was almost full",
            RecordingAutoStopReason.STORAGE_CRITICAL.autoStopSnackbarMessage(context),
        )
    }
}
