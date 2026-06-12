package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingState

/**
 * Gate for resuming a paused session — both the notification's manual Resume action and the
 * AUD-05 auto-resume after a transient focus interruption ends.
 *
 * A resume must never race an in-flight gated stop: when the session is Paused the stop's
 * mutex-protected section is brief (the segment capture is already null) and the shared state
 * stays Paused until the capture handoff lands, so a resume arriving in that window would pass
 * a naive Paused-state check, start a brand-new capture engine that no stop path ever rolls
 * back (an orphaned hot mic with no notification or owner), and repoint the STOPPING journal
 * entry's audioPath at the live half-written segment the already-enqueued finalize worker is
 * about to consume. The check therefore runs both before launching the resume work and again
 * INSIDE the segment-transition mutex immediately before a new engine is started.
 */
object RecordingResumeGuard {
    fun canResume(
        state: RecordingState,
        stopInProgress: Boolean,
    ): Boolean = state is RecordingState.Paused && !stopInProgress
}
