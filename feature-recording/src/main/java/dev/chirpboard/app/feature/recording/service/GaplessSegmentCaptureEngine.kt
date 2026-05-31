package dev.chirpboard.app.feature.recording.service

import java.io.File

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

    val maxAmplitude: Int
}
