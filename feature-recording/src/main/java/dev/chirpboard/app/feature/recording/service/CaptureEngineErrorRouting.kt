package dev.chirpboard.app.feature.recording.service

/**
 * Decision logic for routing a mid-recording capture-engine error callback.
 *
 * Ordering matters and is part of the contract:
 * 1. A destroyed service drops the error — its scopes are dead and no state may be touched.
 * 2. An error from an engine that is no longer the active capture is dropped — whichever
 *    path replaced the engine (pause, stop, restart) owns its audio.
 * 3. Only a live error from the active engine attempts to claim the stop gate; if a stop
 *    already holds the gate the error is informational and that stop owns finalization.
 * 4. Otherwise the claim succeeds and the caller must run the gated stop-with-save path.
 *
 * The gate claim is side-effectful, so [decide] must never invoke it on a dropped error —
 * a stray claim would block the real stop that follows.
 */
internal object CaptureEngineErrorRouting {
    enum class Decision {
        DROP_DESTROYED,
        DROP_STALE_ENGINE,
        INFORMATIONAL_STOP_IN_FLIGHT,
        STOP_WITH_SAVE,
    }

    fun decide(
        destroyed: Boolean,
        engineIsActive: Boolean,
        claimStopGate: () -> Boolean,
    ): Decision =
        when {
            destroyed -> Decision.DROP_DESTROYED
            !engineIsActive -> Decision.DROP_STALE_ENGINE
            !claimStopGate() -> Decision.INFORMATIONAL_STOP_IN_FLIGHT
            else -> Decision.STOP_WITH_SAVE
        }
}
