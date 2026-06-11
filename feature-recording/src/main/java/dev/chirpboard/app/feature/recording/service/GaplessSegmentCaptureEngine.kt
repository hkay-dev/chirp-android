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
 *   segment file and released its audio resources; calling
 *   [GaplessSegmentCaptureEngine.stopAndFinalize] from the callback is safe and returns the
 *   finalized partial segment.
 */
fun interface GaplessCaptureErrorListener {
    fun onCaptureError(error: GaplessCaptureError)
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

    fun releaseWithoutSave()

    /**
     * Registers [listener] to learn about mid-recording capture death (see
     * [GaplessCaptureErrorListener] for the threading contract). Pass null to clear.
     * The listener survives pause/resume cycles until explicitly cleared.
     * Default is a no-op for implementations without a capture thread.
     */
    fun setCaptureErrorListener(listener: GaplessCaptureErrorListener?) {}

    /**
     * Best-effort, non-destructive resource release after a bounded stop timed out:
     * releases audio hardware and closes encoders/writers but never deletes segment files.
     * Safe to call from any thread; may block if the engine is wedged, so callers should
     * invoke it from a disposable background thread. Default is a no-op.
     */
    fun releaseAfterStopTimeout() {}

    val maxAmplitude: Int
}
