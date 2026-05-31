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
            CaptureStopResult.TimedOut(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CaptureStopResult.Failed(e)
        } catch (e: ExecutionException) {
            CaptureStopResult.Failed(e.cause ?: e)
        } finally {
            executor.shutdownNow()
        }
    }
}

private object CaptureStopThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "bounded-capture-stop").apply {
            isDaemon = true
        }
}
