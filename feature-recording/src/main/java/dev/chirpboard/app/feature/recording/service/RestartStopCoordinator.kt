package dev.chirpboard.app.feature.recording.service

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates restart claims on the shared [StopRequestGate].
 *
 * A restart claims the gate for the duration of its teardown so it can never interleave
 * with an in-flight stop; if a stop already holds the gate the restart is refused and the
 * stop wins. The converse direction is remembered instead of dropped: a stop request that
 * arrives while a restart holds the gate marks a pending stop, so the restart finishes
 * discarding the old session but honors the stop by not starting a new recording.
 */
internal class RestartStopCoordinator(
    private val gate: StopRequestGate,
) {
    private val restartHoldsGate = AtomicBoolean(false)
    private val stopRequestedDuringRestart = AtomicBoolean(false)

    /**
     * Claims the gate for a restart teardown. Returns false (refusal) when a stop or an
     * earlier restart already holds the gate; a refused restart never touches the gate,
     * so the in-flight claim is preserved.
     */
    fun tryBeginRestart(): Boolean {
        if (!gate.tryBegin()) {
            return false
        }
        stopRequestedDuringRestart.set(false)
        restartHoldsGate.set(true)
        return true
    }

    /**
     * Releases the claim taken by [tryBeginRestart]. Only call from the restart path that
     * successfully claimed the gate — a claim held by a stop is never reset here.
     */
    fun finishRestart() {
        restartHoldsGate.set(false)
        gate.reset()
    }

    /**
     * Classifies a stop request whose own [StopRequestGate.tryBegin] failed. When the gate
     * is held by a restart the stop is recorded as pending (consumed by the restart via
     * [consumeStopRequestedDuringRestart]); otherwise it is a true duplicate of an
     * in-flight stop.
     */
    fun classifyRejectedStop(): RejectedStop =
        if (restartHoldsGate.get()) {
            stopRequestedDuringRestart.set(true)
            RejectedStop.QUEUED_BEHIND_RESTART
        } else {
            RejectedStop.DUPLICATE_STOP
        }

    /**
     * True when a stop arrived during the restart teardown. Consuming clears the flag so
     * the pending stop is honored exactly once.
     */
    fun consumeStopRequestedDuringRestart(): Boolean = stopRequestedDuringRestart.getAndSet(false)

    enum class RejectedStop {
        QUEUED_BEHIND_RESTART,
        DUPLICATE_STOP,
    }
}
