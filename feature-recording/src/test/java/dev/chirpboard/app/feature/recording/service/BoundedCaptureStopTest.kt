package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedCaptureStopTest {
    @Test
    fun stop_whenStopCompletes_returnsCompletedFile() {
        val finalized = File("finalized.wav")
        val engine = FakeGaplessCapture(onStop = { finalized })

        val result = engine.stopAndFinalizeBounded(timeoutMs = 1_000)

        assertEquals(CaptureStopResult.Completed(finalized), result)
    }

    @Test
    fun stop_whenStopThrows_returnsFailedWithCause() {
        val failure = IllegalStateException("muxer broke")
        val engine = FakeGaplessCapture(onStop = { throw failure })

        val result = engine.stopAndFinalizeBounded(timeoutMs = 1_000)

        assertTrue(result is CaptureStopResult.Failed)
        assertEquals(failure, (result as CaptureStopResult.Failed).cause)
    }

    @Test
    fun stop_onTimeout_releasesEngineResources() {
        val released = CountDownLatch(1)
        val engine =
            object : FakeGaplessCapture(
                onStop = {
                    Thread.sleep(STUCK_STOP_SLEEP_MS)
                    null
                },
            ) {
                override fun releaseAfterStopTimeout() {
                    released.countDown()
                }
            }

        val result = engine.stopAndFinalizeBounded(timeoutMs = 100)

        assertEquals(CaptureStopResult.TimedOut(100), result)
        assertTrue(
            "releaseAfterStopTimeout was not invoked after timeout",
            released.await(2, TimeUnit.SECONDS),
        )
    }

    private companion object {
        const val STUCK_STOP_SLEEP_MS = 10_000L
    }
}

private open class FakeGaplessCapture(
    private val onStop: () -> File?,
) : GaplessSegmentCaptureEngine {
    override suspend fun start(segmentFile: File) = Unit

    override fun rotateSegment(nextSegmentFile: File): SegmentRotationResult =
        SegmentRotationResult.Failed("unused")

    override fun cancelPendingRotation() = Unit

    override fun pauseAndFinalizeSegment(): File? = null

    override suspend fun resume(nextSegmentFile: File) = Unit

    override fun stopAndFinalize(): File? = onStop()

    override fun releaseWithoutSave() = Unit

    override val maxAmplitude: Int = 0
}
