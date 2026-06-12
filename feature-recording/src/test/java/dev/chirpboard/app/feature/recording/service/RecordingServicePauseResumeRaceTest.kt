package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIC-001 race: the transient-focus pause is asynchronous (IO under the segment-transition
 * mutex) while the focus-regain callback is synchronous, so a quick LOSS_TRANSIENT -> GAIN
 * pair can deliver the regain BEFORE the pause lands — without the latch the regain read a
 * not-yet-set pausedByFocusLoss, bailed, and the session stayed Paused forever.
 * [FocusPauseResumeLatch] is the service's defense; these tests walk the exact
 * interleavings of the focus callbacks against the pause coroutine.
 */
class RecordingServicePauseResumeRaceTest {
    private val latch = FocusPauseResumeLatch()

    @Test
    fun `regain arriving before the focus pause lands is latched and replayed`() {
        // AUDIOFOCUS_LOSS_TRANSIENT: the request flag is set synchronously, then the pause
        // coroutine is launched (and here stalls behind a contended mutex).
        latch.onFocusPauseRequested()

        // AUDIOFOCUS_GAIN arrives while the pause is still queued: nothing is paused yet,
        // so resuming now must be refused — but the regain must be latched, not dropped.
        assertFalse(latch.shouldResumeOnFocusRegain())

        // The pause coroutine finally lands inside the mutex...
        latch.onPauseLanded(byFocusLoss = true)

        // ...and the end-of-pause drain replays the regain: the session ends up Recording
        // (the drain re-invokes resumeAfterFocusRegained), not Paused forever.
        assertTrue(latch.onPauseAttemptFinished())
        assertTrue(latch.shouldResumeOnFocusRegain())
    }

    @Test
    fun `regain after the focus pause landed resumes immediately and latches nothing`() {
        latch.onFocusPauseRequested()
        latch.onPauseLanded(byFocusLoss = true)
        assertFalse(latch.onPauseAttemptFinished())

        // The uncontended ordering: pause first, GAIN second — straight auto-resume.
        assertTrue(latch.shouldResumeOnFocusRegain())
    }

    @Test
    fun `manual pause plus regain stays paused`() {
        // A manual pause never registers a focus-pause request, so a stray GAIN has
        // nothing to resume and nothing to latch.
        latch.onPauseLanded(byFocusLoss = false)
        assertFalse(latch.onPauseAttemptFinished())

        assertFalse(latch.shouldResumeOnFocusRegain())
        assertFalse(latch.onPauseAttemptFinished())
    }

    @Test
    fun `manual pause that wins the race consumes the latched regain without resuming`() {
        // Focus loss queues a pause and the regain arrives early...
        latch.onFocusPauseRequested()
        assertFalse(latch.shouldResumeOnFocusRegain())

        // ...but a manual pause acquires the mutex first and lands. Its drain must swallow
        // the latched regain: a manual pause is never auto-resumed.
        latch.onPauseLanded(byFocusLoss = false)
        assertFalse(latch.onPauseAttemptFinished())

        // The superseded focus-pause attempt then no-ops (state is already Paused); its
        // drain finds nothing left to replay either.
        assertFalse(latch.onPauseAttemptFinished())
        assertFalse(latch.shouldResumeOnFocusRegain())
    }

    @Test
    fun `reset drops a latched regain so a permanent-loss stop wins`() {
        // LOSS_TRANSIENT -> GAIN -> LOSS_PERMANENT: the permanent loss routes to the gated
        // stop (stop-with-save), which resets the latch the moment it claims the session.
        latch.onFocusPauseRequested()
        assertFalse(latch.shouldResumeOnFocusRegain())
        latch.reset()

        // The still-queued transient pause may land afterwards while the stop is in
        // flight; the dropped latch must never auto-resume into the gated stop.
        latch.onPauseLanded(byFocusLoss = true)
        assertFalse(latch.onPauseAttemptFinished())
    }

    @Test
    fun `pause attempt that no-ops clears the in-flight request`() {
        // The focus pause found the session already non-Recording and did nothing: the
        // request is over, so a later GAIN must not latch against it forever.
        latch.onFocusPauseRequested()
        assertFalse(latch.onPauseAttemptFinished())

        assertFalse(latch.shouldResumeOnFocusRegain())
    }

    @Test
    fun `reset clears the focus-pause marker used by the notification status line`() {
        latch.onPauseLanded(byFocusLoss = true)
        assertTrue(latch.pausedByFocusLoss)

        latch.reset()

        assertFalse(latch.pausedByFocusLoss)
    }
}
