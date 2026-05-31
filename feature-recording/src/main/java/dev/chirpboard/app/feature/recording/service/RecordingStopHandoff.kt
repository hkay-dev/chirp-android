package dev.chirpboard.app.feature.recording.service

import java.util.UUID

internal object RecordingStopHandoff {
    suspend fun handoff(
        sessionId: UUID?,
        stopCapture: suspend () -> Unit,
        captureSnapshot: () -> StopSnapshot?,
        markAbandoned: suspend (sessionId: UUID?, recordingId: UUID?) -> Unit,
        markStopping: suspend (sessionId: UUID) -> Unit,
        enqueueFinalize: suspend (snapshot: StopSnapshot, sessionId: UUID?) -> Unit,
        onCaptureStopHandoff: (recordingId: UUID?) -> Unit,
    ): StopSnapshot? {
        stopCapture()

        val snapshot = captureSnapshot()
        val recordingId = snapshot?.recordingId
        if (snapshot == null || recordingId == null) {
            markAbandoned(sessionId, recordingId)
            onCaptureStopHandoff(recordingId)
            return snapshot
        }

        sessionId?.let { markStopping(it) }
        enqueueFinalize(snapshot, sessionId)
        onCaptureStopHandoff(recordingId)
        return snapshot
    }
}
