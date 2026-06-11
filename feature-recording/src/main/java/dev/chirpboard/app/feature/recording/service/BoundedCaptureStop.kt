package dev.chirpboard.app.feature.recording.service

import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

sealed class CaptureStopResult {
    data class Completed(val finalizedFile: File?) : CaptureStopResult()
    data class TimedOut(val timeoutMs: Long) : CaptureStopResult()
    data class Failed(val cause: Throwable) : CaptureStopResult()
}

internal object BoundedCaptureStop {
    fun stop(
        capture: GaplessSegmentCaptureEngine,
        timeoutMs: Long,
    ): CaptureStopResult {
        val executor = Executors.newSingleThreadExecutor(CaptureStopThreadFactory)
        val future = executor.submit<File?> { capture.stopAndFinalize() }
        return try {
            CaptureStopResult.Completed(future.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            future.cancel(true)
            releaseEngineResources(capture)
            CaptureStopResult.TimedOut(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            releaseEngineResources(capture)
            CaptureStopResult.Failed(e)
        } catch (e: ExecutionException) {
            releaseEngineResources(capture)
            CaptureStopResult.Failed(e.cause ?: e)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Runs after a stop timed out, threw, or was interrupted, so the engine never keeps the
     * mic or an open encoder/writer past a failed stop. The stuck stop may still hold engine
     * locks, so the non-destructive cleanup runs on a disposable daemon thread instead of
     * blocking the caller; it releases the mic and closes encoders/writers as soon as the
     * engine lock frees up, without deleting segment files.
     */
    private fun releaseEngineResources(capture: GaplessSegmentCaptureEngine) {
        Thread(
            { runCatching { capture.releaseAfterStopTimeout() } },
            "bounded-capture-cleanup",
        ).apply {
            isDaemon = true
            start()
        }
    }
}

private object CaptureStopThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "bounded-capture-stop").apply {
            isDaemon = true
        }
}
