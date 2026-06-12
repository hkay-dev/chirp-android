package dev.chirpboard.app.feature.recording.ui

import dev.chirpboard.app.feature.recording.service.RecordingAutoPauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AUD-02/AUD-05/ERR-14: the in-app advisory resolution must mirror the notification status
 * line (RecordingService.currentRecordingStatusText) — same conditions, same priority.
 */
class RecordingSessionAdvisoryTest {
    @Test
    fun `no advisory when nothing is reported`() {
        assertNull(
            resolveSessionAdvisory(
                autoPauseReason = null,
                silenceDetected = false,
                storageLow = false,
            ),
        )
    }

    @Test
    fun `focus pause wins over silence and storage`() {
        assertEquals(
            RecordingSessionAdvisory.PAUSED_BY_FOCUS_LOSS,
            resolveSessionAdvisory(
                autoPauseReason = RecordingAutoPauseReason.FOCUS_LOST_TRANSIENT,
                silenceDetected = true,
                storageLow = true,
            ),
        )
    }

    @Test
    fun `silence wins over storage`() {
        assertEquals(
            RecordingSessionAdvisory.SILENCED,
            resolveSessionAdvisory(
                autoPauseReason = null,
                silenceDetected = true,
                storageLow = true,
            ),
        )
    }

    @Test
    fun `storage low surfaces alone`() {
        assertEquals(
            RecordingSessionAdvisory.STORAGE_LOW,
            resolveSessionAdvisory(
                autoPauseReason = null,
                silenceDetected = false,
                storageLow = true,
            ),
        )
    }

    @Test
    fun `every advisory maps to the matching notification string`() {
        // Wording parity contract: both surfaces reuse the same resources verbatim.
        assertEquals(
            dev.chirpboard.app.feature.recording.R.string.rec_notification_paused_focus,
            RecordingSessionAdvisory.PAUSED_BY_FOCUS_LOSS.advisoryStringRes(),
        )
        assertEquals(
            dev.chirpboard.app.feature.recording.R.string.rec_notification_silence,
            RecordingSessionAdvisory.SILENCED.advisoryStringRes(),
        )
        assertEquals(
            dev.chirpboard.app.feature.recording.R.string.rec_notification_storage_low,
            RecordingSessionAdvisory.STORAGE_LOW.advisoryStringRes(),
        )
    }
}
