package dev.chirpboard.app.feature.recording.service

import java.util.UUID

internal object RecordingStopHandoff {
    suspend fun handoff(
        snapshot: StopSnapshot?,
        sessionId: UUID?,
        stopCapture: suspend () -> Unit,
        markAbandoned: suspend (sessionId: UUID?, recordingId: UUID?) -> Unit,
        markStopping: suspend (sessionId: UUID) -> Unit,
        enqueueFinalize: suspend (snapshot: StopSnapshot, sessionId: UUID?) -> Unit,
        onCaptureStopHandoff: (recordingId: UUID?) -> Unit,
    ) {
        stopCapture()

        val recordingId = snapshot?.recordingId
        if (snapshot == null || recordingId == null) {
            markAbandoned(sessionId, recordingId)
            onCaptureStopHandoff(recordingId)
            return
        }

        sessionId?.let { markStopping(it) }
        enqueueFinalize(snapshot, sessionId)
        onCaptureStopHandoff(recordingId)
    }
}
