package dev.chirpboard.app

import java.util.concurrent.CountDownLatch
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufDecodeWatchdogTest {
    @Test
    fun `timeout policy is bounded and scales with audio`() {
        val policy = GgufDecodeWatchdogPolicy()

        assertEquals(30_000L, policy.timeoutMs(1_000L))
        assertEquals(135_000L, policy.timeoutMs(60_000L))
        assertEquals(600_000L, policy.timeoutMs(300_000L))
    }

    @Test
    fun `a decode that finishes with a result after the deadline is kept, not discarded`() = runTest {
        // The timer only REQUESTS cancellation; the native abort fires between decode
        // steps. A decode on its final step completes normally with the full transcript,
        // and that result must win over the stale timed-out flag.
        val scheduler = ManualWatchdogScheduler()
        var nowMs = 100L
        var cancelledOperation = 0L
        var reportedTimedOut = true
        val watchdog =
            GgufDecodeWatchdog(
                policy = GgufDecodeWatchdogPolicy(minimumTimeoutMs = 10, graceMs = 0, audioMultiplier = 1.0),
                scheduler = scheduler,
                nowMs = { nowMs },
            )

        val result =
            watchdog.run(
                audioDurationMs = 10,
                beginDecode = { 42L },
                cancelDecode = { operationId ->
                    cancelledOperation = operationId
                    true
                },
                operation = {
                    nowMs = 110L
                    scheduler.fire()
                    "full transcript"
                },
                wasAborted = { false },
                onNativeFinished = { _, timedOut, _ -> reportedTimedOut = timedOut },
            )

        assertEquals("full transcript", (result as GgufWatchdogResult.Completed).value)
        assertEquals(42L, cancelledOperation)
        assertTrue(!reportedTimedOut)
    }

    @Test
    fun `a decode the cancel actually aborted reports timed out`() = runTest {
        val scheduler = ManualWatchdogScheduler()
        var reportedTimedOut = false
        val watchdog =
            GgufDecodeWatchdog(
                policy = GgufDecodeWatchdogPolicy(minimumTimeoutMs = 10, graceMs = 0, audioMultiplier = 1.0),
                scheduler = scheduler,
                nowMs = { 0L },
            )

        val result =
            watchdog.run(
                audioDurationMs = 10,
                beginDecode = { 42L },
                cancelDecode = { true },
                operation = {
                    scheduler.fire()
                    null
                },
                wasAborted = { it == null },
                onNativeFinished = { _, timedOut, _ -> reportedTimedOut = timedOut },
            )

        assertTrue(result is GgufWatchdogResult.TimedOut)
        assertTrue(reportedTimedOut)
    }

    @Test
    fun `caller cancellation requests native cancellation`() = runTest {
        val scheduler = ManualWatchdogScheduler()
        var cancelledOperation = 0L
        val nativeStarted = CountDownLatch(1)
        val nativeCancelled = CountDownLatch(1)
        val watchdog =
            GgufDecodeWatchdog(
                scheduler = scheduler,
                nowMs = { 0L },
            )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val result =
                launch(dispatcher) {
                    watchdog.run(
                        audioDurationMs = 1_000,
                        beginDecode = { 7L },
                        cancelDecode = { operationId ->
                            cancelledOperation = operationId
                            nativeCancelled.countDown()
                            true
                        },
                        operation = {
                            nativeStarted.countDown()
                            check(nativeCancelled.await(1, TimeUnit.SECONDS))
                            "unused"
                        },
                    )
                }
            assertTrue(nativeStarted.await(1, TimeUnit.SECONDS))
            result.cancelAndJoin()
        }

        assertEquals(7L, cancelledOperation)
    }

    @Test
    fun `deadline keeps a completed result when native cancellation loses the race`() = runTest {
        val scheduler = ManualWatchdogScheduler()
        val watchdog =
            GgufDecodeWatchdog(
                policy = GgufDecodeWatchdogPolicy(minimumTimeoutMs = 1, graceMs = 0, audioMultiplier = 1.0),
                scheduler = scheduler,
                nowMs = { 1L },
            )

        val result =
            watchdog.run(
                audioDurationMs = 1,
                beginDecode = { 9L },
                cancelDecode = { false },
                operation = {
                    scheduler.fire()
                    "complete"
                },
            )

        assertEquals("complete", (result as GgufWatchdogResult.Completed).value)
    }

    @Test
    fun `cancellation from the operation propagates instead of reporting a decode failure`() = runTest {
        val scheduler = ManualWatchdogScheduler()
        var cancelledOperation = 0L
        val watchdog =
            GgufDecodeWatchdog(
                scheduler = scheduler,
                nowMs = { 0L },
            )

        val thrown =
            runCatching {
                watchdog.run<String>(
                    audioDurationMs = 1_000,
                    beginDecode = { 11L },
                    cancelDecode = { operationId ->
                        cancelledOperation = operationId
                        true
                    },
                    operation = { throw CancellationException("caller went away") },
                )
            }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(11L, cancelledOperation)
        // The timer must be dropped too, or it fires cancelDecode against a later operation.
        scheduler.fire()
        assertEquals(11L, cancelledOperation)
    }

    @Test
    fun `cancellation from beginDecode propagates instead of reporting a decode failure`() = runTest {
        val watchdog = GgufDecodeWatchdog(scheduler = ManualWatchdogScheduler(), nowMs = { 0L })

        val thrown =
            runCatching {
                watchdog.run<String>(
                    audioDurationMs = 1_000,
                    beginDecode = { throw CancellationException("caller went away") },
                    cancelDecode = { true },
                    operation = { "unused" },
                )
            }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `cancellation from the telemetry callback propagates`() = runTest {
        val watchdog = GgufDecodeWatchdog(scheduler = ManualWatchdogScheduler(), nowMs = { 0L })

        val thrown =
            runCatching {
                watchdog.run(
                    audioDurationMs = 1_000,
                    beginDecode = { 3L },
                    cancelDecode = { true },
                    operation = { "text" },
                    onNativeFinished = { _, _, _ -> throw CancellationException("caller went away") },
                )
            }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `a decode error that is not cancellation is still reported as failed`() = runTest {
        val watchdog = GgufDecodeWatchdog(scheduler = ManualWatchdogScheduler(), nowMs = { 0L })

        val result =
            watchdog.run<String>(
                audioDurationMs = 1_000,
                beginDecode = { 5L },
                cancelDecode = { true },
                operation = { throw IllegalStateException("native blew up") },
            )

        assertTrue((result as GgufWatchdogResult.Failed).error is IllegalStateException)
    }

    private class ManualWatchdogScheduler : GgufWatchdogScheduler {
        private var task: (() -> Unit)? = null

        override fun schedule(
            delayMs: Long,
            task: () -> Unit,
        ): GgufScheduledTask {
            this.task = task
            return GgufScheduledTask { this.task = null }
        }

        fun fire() {
            task?.invoke()
        }
    }
}
