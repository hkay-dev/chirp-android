package dev.chirpboard.app.feature.recording.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingFinalizeWorkerTest {
    @Test
    fun `recording finalize foreground type matches manifest data sync type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            recordingFinalizeForegroundServiceType(),
        )
    }
}
