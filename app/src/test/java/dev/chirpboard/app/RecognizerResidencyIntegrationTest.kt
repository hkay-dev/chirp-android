package dev.chirpboard.app

import android.util.Log
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PRF-2 / REL-09 integration races, beyond the policy-level tests that stub
 * [RecognizerManager]: here the REAL manager (real decision mutex, real lease counter,
 * real residency-listener wiring) runs against the idle timer on virtual time. Pins:
 *  - a usage lease held when the idle window expires blocks the release and the lease's
 *    return restarts a FULL idle window (touch-on-completion);
 *  - live transcription-queue work blocks the real release until the queue drains;
 *  - an explicit user release cancels the armed timer through the residency callback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognizerResidencyIntegrationTest {
    private val testScope = TestScope(StandardTestDispatcher())

    /** Fully virtual wall clock shared by the manager's recency stamps and the policy. */
    private val virtualClock: () -> Long = { WALL_BASE_MS + testScope.testScheduler.currentTime }

    private val recordingStateManager = RecordingStateManager()
    private val transcriptionQueueBusy = AtomicBoolean(false)
    private val repository =
        mockk<RecordingRepository> {
            every { getRecordingsByStatus(any()) } answers {
                val busy =
                    transcriptionQueueBusy.get() &&
                        firstArg<RecordingStatus>() == RecordingStatus.PENDING_TRANSCRIPTION
                flowOf(
                    RepositoryFlowState<List<Recording>>(
                        value = if (busy) listOf(mockk()) else emptyList(),
                    ),
                )
            }
        }

    private val recognizer = readyRecognizer()

    private fun readyRecognizer(): SherpaRecognizer =
        mockk {
            every { isReady } returns true
            coJustRun { release() }
        }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        RecognizerManager.resetUsageStateForTest()
        RecognizerManager.clock = virtualClock
    }

    @After
    fun tearDown() {
        RecognizerManager.resetUsageStateForTest()
        unmockkStatic(Log::class)
    }

    private fun startedPolicy(): RecognizerIdleReleasePolicy =
        RecognizerIdleReleasePolicy(
            recordingStateManager = recordingStateManager,
            recordingRepository = dagger.Lazy { repository },
        ).apply {
            scope = testScope.backgroundScope
            clock = virtualClock
            idleCutoffMs = IDLE_CUTOFF_MS
            busyRecheckMs = BUSY_RECHECK_MS
            start()
        }

    @Test
    fun `idle timer frees the model through the real manager one full window after last use`() =
        testScope.runTest {
            RecognizerManager.installRecognizerForTest(recognizer)
            RecognizerManager.peekReadyRecognizer() // stamp recency at t=0
            startedPolicy()

            advanceTimeBy(IDLE_CUTOFF_MS - 1_000)
            runCurrent()
            assertTrue("released before the idle cutoff", RecognizerManager.isResident())

            advanceTimeBy(2_000)
            runCurrent()
            assertFalse("still resident after the idle cutoff", RecognizerManager.isResident())
            coVerify(exactly = 1) { recognizer.release() }
        }

    @Test
    fun `lease held at window expiry blocks the release and its return restarts a full window`() =
        testScope.runTest {
            RecognizerManager.installRecognizerForTest(recognizer)
            RecognizerManager.peekReadyRecognizer()
            startedPolicy()

            // A chunked decode is mid-flight when the idle deadline passes.
            val decodeFinished = CompletableDeferred<Unit>()
            val decodeJob = launch { RecognizerManager.withUsageLease { decodeFinished.await() } }
            runCurrent()
            assertEquals(1, RecognizerManager.activeLeaseCount())

            advanceTimeBy(IDLE_CUTOFF_MS + 1_000)
            runCurrent()
            assertTrue("released under an active lease", RecognizerManager.isResident())
            coVerify(exactly = 0) { recognizer.release() }

            // The decode completes; touch-on-completion restarts the idle countdown.
            decodeFinished.complete(Unit)
            decodeJob.join()
            advanceTimeBy(IDLE_CUTOFF_MS - 2_000)
            runCurrent()
            assertTrue(
                "released before a full window elapsed after the lease returned",
                RecognizerManager.isResident(),
            )

            advanceTimeBy(BUSY_RECHECK_MS + 3_000)
            runCurrent()
            assertFalse(RecognizerManager.isResident())
            coVerify(exactly = 1) { recognizer.release() }
        }

    @Test
    fun `new lease cannot enter while a release decision owns the manager`() =
        testScope.runTest {
            RecognizerManager.installRecognizerForTest(recognizer)
            RecognizerManager.peekReadyRecognizer()
            val externalCheckStarted = CompletableDeferred<Unit>()
            val finishExternalCheck = CompletableDeferred<Unit>()
            val releaseJob =
                launch {
                    RecognizerManager.releaseIfUnused(
                        minIdleMs = 0L,
                        nowMs = virtualClock(),
                        isExternallyBusy = {
                            externalCheckStarted.complete(Unit)
                            finishExternalCheck.await()
                            false
                        },
                    )
                }
            externalCheckStarted.await()

            val leaseEntered = CompletableDeferred<Unit>()
            val leaseJob = launch { RecognizerManager.withUsageLease { leaseEntered.complete(Unit) } }
            runCurrent()
            assertFalse("lease entered during an in-progress release decision", leaseEntered.isCompleted)

            finishExternalCheck.complete(Unit)
            releaseJob.join()
            leaseJob.join()

            assertTrue(leaseEntered.isCompleted)
            assertEquals(0, RecognizerManager.activeLeaseCount())
            coVerify(exactly = 1) { recognizer.release() }
        }

    @Test
    fun `live transcription queue blocks the real release until the queue drains`() =
        testScope.runTest {
            transcriptionQueueBusy.set(true)
            RecognizerManager.installRecognizerForTest(recognizer)
            RecognizerManager.peekReadyRecognizer()
            startedPolicy()

            advanceTimeBy(IDLE_CUTOFF_MS + 1_000)
            runCurrent()
            assertTrue("released while the queue had live work", RecognizerManager.isResident())
            coVerify(exactly = 0) { recognizer.release() }

            transcriptionQueueBusy.set(false)
            advanceTimeBy(BUSY_RECHECK_MS)
            runCurrent()
            assertFalse("not released after the queue drained", RecognizerManager.isResident())
            coVerify(exactly = 1) { recognizer.release() }
        }

    @Test
    fun `explicit release cancels the timer through the real residency callback`() =
        testScope.runTest {
            RecognizerManager.installRecognizerForTest(recognizer)
            RecognizerManager.peekReadyRecognizer()
            startedPolicy()
            runCurrent()

            // User frees the model explicitly (delete model / free memory): the manager's
            // onRecognizerReleased callback must cancel the armed timer.
            RecognizerManager.releaseRecognizer()
            runCurrent()
            coVerify(exactly = 1) { recognizer.release() }

            // A recognizer that becomes resident WITHOUT a residency callback (test seam)
            // must never be reaped by a leftover timer from the previous warm period.
            val nextRecognizer = readyRecognizer()
            RecognizerManager.installRecognizerForTest(nextRecognizer)
            advanceTimeBy((IDLE_CUTOFF_MS + BUSY_RECHECK_MS) * 10)
            runCurrent()

            assertTrue(RecognizerManager.isResident())
            coVerify(exactly = 0) { nextRecognizer.release() }
        }

    private companion object {
        const val WALL_BASE_MS = 1_750_000_000_000L
        const val IDLE_CUTOFF_MS = 60_000L
        const val BUSY_RECHECK_MS = 5_000L
    }
}
