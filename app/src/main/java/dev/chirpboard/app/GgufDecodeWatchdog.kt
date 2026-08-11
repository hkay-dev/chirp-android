package dev.chirpboard.app

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class GgufDecodeWatchdogPolicy(
    val minimumTimeoutMs: Long = 30_000L,
    val graceMs: Long = 15_000L,
    val audioMultiplier: Double = 0.5,
    val maximumTimeoutMs: Long = 90_000L,
) {
    fun timeoutMs(audioDurationMs: Long): Long =
        (audioDurationMs.coerceAtLeast(0L) * audioMultiplier + graceMs)
            .toLong()
            .coerceIn(minimumTimeoutMs, maximumTimeoutMs)
}

internal sealed interface GgufWatchdogResult<out T> {
    data class Completed<T>(
        val value: T,
        val elapsedMs: Long,
    ) : GgufWatchdogResult<T>

    data class TimedOut(
        val elapsedMs: Long,
    ) : GgufWatchdogResult<Nothing>

    data class Failed(
        val error: Throwable,
        val elapsedMs: Long,
    ) : GgufWatchdogResult<Nothing>
}

internal fun interface GgufScheduledTask {
    fun cancel()
}

internal fun interface GgufWatchdogScheduler {
    fun schedule(
        delayMs: Long,
        task: () -> Unit,
    ): GgufScheduledTask
}

private object ExecutorGgufWatchdogScheduler : GgufWatchdogScheduler {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "chirp-gguf-watchdog").apply { isDaemon = true }
        }
    override fun schedule(
        delayMs: Long,
        task: () -> Unit,
    ): GgufScheduledTask {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return GgufScheduledTask { future.cancel(false) }
    }
}

/**
 * Keeps blocking JNI work on the caller's dedicated decode dispatcher. Only the cancellation flag
 * runs on a separate watchdog thread, and the caller waits for native unwind before recovery.
 */
internal class GgufDecodeWatchdog(
    private val policy: GgufDecodeWatchdogPolicy = GgufDecodeWatchdogPolicy(),
    private val scheduler: GgufWatchdogScheduler = ExecutorGgufWatchdogScheduler,
    private val nowMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    suspend fun <T> run(
        audioDurationMs: Long,
        beginDecode: () -> Long,
        cancelDecode: (Long) -> Boolean,
        operation: (Long) -> T,
        wasAborted: (T) -> Boolean = { false },
        onNativeFinished: (result: Result<T>, timedOut: Boolean, elapsedMs: Long) -> Unit = { _, _, _ -> },
    ): GgufWatchdogResult<T> =
        suspendCancellableCoroutine { continuation ->
            val operationId =
                runCatching(beginDecode).getOrElse { error ->
                    continuation.resume(GgufWatchdogResult.Failed(error, 0L))
                    return@suspendCancellableCoroutine
                }
            if (operationId <= 0L) {
                continuation.resume(
                    GgufWatchdogResult.Failed(
                        IllegalStateException("GGUF recognizer is not loaded"),
                        0L,
                    ),
                )
                return@suspendCancellableCoroutine
            }

            val startedAtMs = nowMs()
            val timedOut = AtomicBoolean(false)
            val timeoutTask =
                scheduler.schedule(policy.timeoutMs(audioDurationMs)) {
                    if (!timedOut.get()) {
                        val cancelled = runCatching { cancelDecode(operationId) }.getOrDefault(false)
                        if (cancelled) timedOut.compareAndSet(false, true)
                    }
                }

            continuation.invokeOnCancellation {
                if (!timedOut.get()) runCatching { cancelDecode(operationId) }
            }

            val result = runCatching { operation(operationId) }
            timeoutTask.cancel()
            val elapsedMs = (nowMs() - startedAtMs).coerceAtLeast(0L)
            // The flag only records that cancellation was REQUESTED. The native abort takes
            // effect between decode steps, so a decode on its final step can complete
            // normally after the deadline; that finished result must win over the stale
            // flag or a whole transcript gets discarded and re-decoded from scratch.
            val outcome =
                result.fold(
                    onSuccess = { value ->
                        if (timedOut.get() && wasAborted(value)) {
                            GgufWatchdogResult.TimedOut(elapsedMs)
                        } else {
                            GgufWatchdogResult.Completed(value, elapsedMs)
                        }
                    },
                    onFailure = { error ->
                        if (timedOut.get()) {
                            GgufWatchdogResult.TimedOut(elapsedMs)
                        } else {
                            GgufWatchdogResult.Failed(error, elapsedMs)
                        }
                    },
                )
            runCatching { onNativeFinished(result, outcome is GgufWatchdogResult.TimedOut, elapsedMs) }
            continuation.resume(outcome)
        }
}
