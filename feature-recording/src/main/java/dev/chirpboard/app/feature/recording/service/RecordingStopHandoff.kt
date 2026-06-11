package dev.chirpboard.app.feature.recording.service

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal object RecordingStopHandoff {
    /**
     * Hands a stopped capture off to the finalize queue.
     *
     * The handoff carries the stop [generation] it was issued for and re-verifies it against
     * [stopGeneration] after every suspension point that precedes a side effect. A cancel or
     * restart bumps the generation, so a stale stop from a superseded session can never mark,
     * enqueue, or complete state for the session that replaced it.
     */
    suspend fun handoff(
        sessionId: UUID?,
        generation: Int,
        stopGeneration: AtomicInteger,
        stopCapture: suspend () -> Unit,
        captureSnapshot: () -> StopSnapshot?,
        markAbandoned: suspend (sessionId: UUID?, recordingId: UUID?) -> Unit,
        markStopping: suspend (sessionId: UUID) -> Unit,
        enqueueFinalize: suspend (snapshot: StopSnapshot, sessionId: UUID?) -> Unit,
        onCaptureStopHandoff: (recordingId: UUID?) -> Unit,
        onStaleHandoff: () -> Unit = {},
    ): StopSnapshot? {
        stopCapture()

        if (generation != stopGeneration.get()) {
            onStaleHandoff()
            return null
        }

        val snapshot = captureSnapshot()
        val recordingId = snapshot?.recordingId
        if (snapshot == null || recordingId == null) {
            markAbandoned(sessionId, recordingId)
            onCaptureStopHandoff(recordingId)
            return snapshot
        }

        sessionId?.let { markStopping(it) }

        if (generation != stopGeneration.get()) {
            onStaleHandoff()
            return null
        }

        enqueueFinalize(snapshot, sessionId)
        onCaptureStopHandoff(recordingId)
        return snapshot
    }
}
