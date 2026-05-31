package dev.chirpboard.app.feature.recording.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class RecordingFinalizeWorkerTest {
    @Test
    fun `terminal finalize outcomes complete worker node`() {
        assertEquals(
            androidx.work.ListenableWorker.Result.success(),
            finalizeWorkerResultFor(StopPersistenceResult.NoAudioFile),
        )
        assertEquals(
            androidx.work.ListenableWorker.Result.success(),
            finalizeWorkerResultFor(StopPersistenceResult.PersistenceFailed("failed")),
        )
    }

    @Test
    fun `successful finalize outcomes complete worker node`() {
        val recordingId = UUID.randomUUID()

        assertEquals(
            androidx.work.ListenableWorker.Result.success(),
            finalizeWorkerResultFor(StopPersistenceResult.SavedAndQueued(recordingId)),
        )
        assertEquals(
            androidx.work.ListenableWorker.Result.success(),
            finalizeWorkerResultFor(
                StopPersistenceResult.SavedPendingRecovery(
                    recordingId = recordingId,
                    message = "queued for recovery",
                    cause = RuntimeException("queue failed"),
                ),
            ),
        )
    }

    @Test
    fun `recording finalize foreground type matches manifest data sync type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            recordingFinalizeForegroundServiceType(),
        )
    }
}
