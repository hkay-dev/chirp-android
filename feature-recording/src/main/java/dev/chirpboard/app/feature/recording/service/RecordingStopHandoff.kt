package dev.chirpboard.app.feature.recording.service

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal object RecordingStopHandoff {
    /**
     * Hands a stopped capture off to the finalize queue.
     *
     * The handoff carries the stop [generation] it was issued for. A cancel or restart bumps
     * the generation, so a stale stop from a superseded session must never mark, enqueue, or
     * complete state for the session that replaced it. The staleness verdict is detected once
     * per side-effect boundary by exactly one layer:
     *  - [stopCapture] runs the capture stop inside the segment-transition mutex and returns
     *    [CaptureStopHandoffResult.StaleGeneration] when a supersede was observed there (the
     *    mutex-protected layer already skipped its own journal commit). The handoff trusts
     *    that single verdict instead of re-reading [stopGeneration] itself.
     *  - The handoff still re-checks [stopGeneration] after [markStopping], because that
     *    suspension point happens outside the stopper's mutex and a supersede can land there.
     */
    suspend fun handoff(
        sessionId: UUID?,
        generation: Int,
        stopGeneration: AtomicInteger,
        stopCapture: suspend () -> CaptureStopHandoffResult,
        captureSnapshot: () -> StopSnapshot?,
        markAbandoned: suspend (sessionId: UUID?, recordingId: UUID?) -> Unit,
        markStopping: suspend (sessionId: UUID) -> Unit,
        enqueueFinalize: suspend (snapshot: StopSnapshot, sessionId: UUID?) -> Unit,
        onCaptureStopHandoff: (recordingId: UUID?) -> Unit,
        onStaleHandoff: () -> Unit = {},
    ): StopSnapshot? {
        if (stopCapture() is CaptureStopHandoffResult.StaleGeneration) {
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
