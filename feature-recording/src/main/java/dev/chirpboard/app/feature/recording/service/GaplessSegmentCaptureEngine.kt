package dev.chirpboard.app.feature.recording.service

import java.io.File

/** Describes a fatal mid-recording capture failure (for example a dead audio source). */
data class GaplessCaptureError(
    val message: String,
    val audioRecordErrorCode: Int? = null,
)

/**
 * Notified when the capture thread dies mid-recording.
 *
 * Threading contract (real engines):
 * - Invoked on the engine's capture thread, never on the caller's thread.
 * - Invoked at most once per [GaplessSegmentCaptureEngine.start]/[GaplessSegmentCaptureEngine.resume]
 *   cycle, and only when no stop/pause/release had already been requested.
 * - Invoked with no engine locks held, after the engine has already finalized the current
 *   segment file and released its audio resources. Calling
 *   [GaplessSegmentCaptureEngine.stopAndFinalize] from the callback never deadlocks and
 *   returns the finalized partial segment, but if it races a concurrent stop/pause that is
 *   already joining the capture thread it can block both threads for up to the engine's
 *   capture-join timeout; prefer hopping off the capture thread (for example by launching
 *   a coroutine) before issuing control calls.
 */
fun interface GaplessCaptureErrorListener {
    fun onCaptureError(error: GaplessCaptureError)
}

/**
 * Notified (on the engine's capture thread) when capture transitions into or out of
 * sustained digital silence — every sample exactly zero. Pure zeros are the signature of
 * a silenced AudioRecord client: another app holds the microphone under the
 * concurrent-capture policy, or the mic privacy toggle is off. Reads keep succeeding in
 * that state, so without this signal a session can record minutes of nothing while
 * looking perfectly live.
 */
fun interface GaplessSilenceListener {
    fun onSilenceStateChanged(silenced: Boolean)
}

interface GaplessSegmentCaptureEngine {
    suspend fun start(segmentFile: File)

    fun rotateSegment(nextSegmentFile: File): SegmentRotationResult

    fun cancelPendingRotation()

    fun pauseAndFinalizeSegment(): File?

    suspend fun resume(nextSegmentFile: File)

    fun stopAndFinalize(): File?

    fun stopAndFinalizeBounded(timeoutMs: Long): CaptureStopResult =
        BoundedCaptureStop.stop(this, timeoutMs)

    /** Pushes the active segment's bytes to stable storage. */
    fun checkpointDurability(): Boolean = true

    /** Monotonic PCM payload bytes captured by this live engine. */
    val capturedAudioBytes: Long?
        get() = null

    fun releaseWithoutSave()

    /**
     * Registers [listener] to learn about mid-recording capture death (see
     * [GaplessCaptureErrorListener] for the threading contract). Pass null to clear.
     * The listener survives pause/resume cycles until explicitly cleared.
     * Default is a no-op for implementations without a capture thread.
     */
    fun setCaptureErrorListener(listener: GaplessCaptureErrorListener?) {}

    /**
     * Registers [listener] for sustained-silence transitions (see [GaplessSilenceListener]
     * for the threading contract). Pass null to clear. Default is a no-op for
     * implementations without a capture thread.
     */
    fun setSilenceListener(listener: GaplessSilenceListener?) {}

    /**
     * Best-effort, non-destructive resource release after a bounded stop timed out, threw,
     * or was interrupted: releases audio hardware and closes encoders/writers but never
     * deletes segment files. Safe to call from any thread; may block if the engine is
     * wedged, so callers should invoke it from a disposable background thread. Default is
     * a no-op.
     */
    fun releaseAfterStopTimeout() {}

    val maxAmplitude: Int
}
