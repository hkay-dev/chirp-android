package dev.chirpboard.app

import android.util.Log
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceRecognitionSessionCoordinatorTest {
    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    /**
     * Builds a coordinator whose off-main recorder-teardown hop ([VoiceRecognitionSessionCoordinator]'s
     * ioDispatcher) runs on the SAME virtual-time test scheduler as the coordinator scope, so the
     * `withContext(ioDispatcher)` introduced for PERF-5 stays deterministic under `runCurrent()`
     * instead of escaping to a real `Dispatchers.IO` thread.
     */
    private fun TestScope.newCoordinator(
        gate: VoiceRecognitionCaptureGate,
        recorder: VoiceRecognitionSessionCoordinator.RecorderControl,
    ): VoiceRecognitionSessionCoordinator =
        VoiceRecognitionSessionCoordinator(
            scope = this,
            captureGate = gate,
            recorder = recorder,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun `stop arriving mid-start waits for the start and stops the recorder cleanly`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = FakeRecorderControl()
            val coordinator = newCoordinator(gate, recorder)

            val generation = coordinator.issueGeneration()
            val startResult = async { coordinator.start(generation, {}, {}, {}) }
            runCurrent()
            assertTrue(recorder.startRequested)

            val stopResult = async { coordinator.stop(generation) {} }
            runCurrent()
            // The stop must wait for the in-flight start instead of racing it.
            assertFalse(recorder.stopCalled)
            assertTrue(gate.isHeld())

            recorder.completeStart()
            runCurrent()

            assertEquals(VoiceRecognitionSessionCoordinator.StartResult.Started, startResult.await())
            val stop = stopResult.await()
            assertTrue(stop is VoiceRecognitionSessionCoordinator.StopResult.Captured)
            assertEquals(2, (stop as VoiceRecognitionSessionCoordinator.StopResult.Captured).samples.size)
            assertTrue(recorder.stopCalled)
            assertFalse(gate.isHeld())
            assertEquals(RecordingState.Idle, manager.state.value)
        }

    @Test
    fun `cancel arriving mid-start waits for the start and leaves the recorder stopped`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = FakeRecorderControl()
            val coordinator = newCoordinator(gate, recorder)

            val generation = coordinator.issueGeneration()
            val startResult = async { coordinator.start(generation, {}, {}, {}) }
            runCurrent()

            val cancelResult = async { coordinator.cancel(generation) }
            runCurrent()
            assertFalse(recorder.cancelCalled)

            recorder.completeStart()
            runCurrent()

            assertEquals(VoiceRecognitionSessionCoordinator.StartResult.Started, startResult.await())
            assertTrue(cancelResult.await())
            assertTrue(recorder.cancelCalled)
            assertFalse(gate.isHeld())
            assertEquals(RecordingState.Idle, manager.state.value)
        }

    @Test
    fun `stop after failed start is stale and the gate stays released`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = FakeRecorderControl()
            val coordinator = newCoordinator(gate, recorder)

            recorder.startSucceeds = false
            recorder.completeStart()

            val generation = coordinator.issueGeneration()
            val startResult = coordinator.start(generation, {}, {}, {})

            assertEquals(VoiceRecognitionSessionCoordinator.StartResult.Failed(null), startResult)
            assertFalse(gate.isHeld())

            val stopResult = coordinator.stop(generation) {}

            assertEquals(VoiceRecognitionSessionCoordinator.StopResult.Stale, stopResult)
            assertFalse(recorder.stopCalled)
        }

    @Test
    fun `superseded start never turns on the recorder`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = FakeRecorderControl()
            val coordinator = newCoordinator(gate, recorder)

            val first = coordinator.issueGeneration()
            coordinator.issueGeneration() // a newer start request supersedes the first

            val result = coordinator.start(first, {}, {}, {})

            assertEquals(VoiceRecognitionSessionCoordinator.StartResult.Superseded, result)
            assertFalse(recorder.startRequested)
            assertFalse(gate.isHeld())
        }

    @Test
    fun `cancel request mark is consumed exactly once and only for its own generation`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = FakeRecorderControl()
            val coordinator = newCoordinator(gate, recorder)

            val cancelled = coordinator.issueGeneration()
            coordinator.markCancelRequested(cancelled)
            val newer = coordinator.issueGeneration()

            // Only the generation whose own client cancelled is suppressed; the active
            // (newer) generation and repeat lookups keep their terminal callbacks.
            assertFalse(coordinator.consumeCancelRequest(newer))
            assertTrue(coordinator.consumeCancelRequest(cancelled))
            assertFalse(coordinator.consumeCancelRequest(cancelled))
        }

    @Test
    fun `superseded start whose client cancelled is reported and its mark consumed`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = FakeRecorderControl()
            val coordinator = newCoordinator(gate, recorder)

            val first = coordinator.issueGeneration()
            // The client cancels the pending start, then a newer start supersedes it.
            coordinator.markCancelRequested(first)
            coordinator.issueGeneration()

            val result = coordinator.start(first, {}, {}, {})

            assertEquals(VoiceRecognitionSessionCoordinator.StartResult.Superseded, result)
            // The service consults the mark to drop the stale terminal BUSY for this
            // session (its cancel was already terminal from the client's perspective).
            assertTrue(coordinator.consumeCancelRequest(first))
            assertFalse(recorder.startRequested)
            assertFalse(gate.isHeld())
        }

    @Test
    fun `cancelling an in-flight start releases the gate and leaves the recorder stopped`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = FakeRecorderControl()
            val coordinator = newCoordinator(gate, recorder)

            val generation = coordinator.issueGeneration()
            val startJob = launch { coordinator.start(generation, {}, {}, {}) }
            runCurrent()
            assertTrue(recorder.startRequested)
            assertTrue(gate.isHeld())

            startJob.cancel()
            runCurrent()

            assertTrue(recorder.cancelCalled)
            assertFalse(gate.isHeld())
            assertEquals(RecordingState.Idle, manager.state.value)

            // The next session must be able to acquire the gate again.
            recorder.completeStart()
            val nextGeneration = coordinator.issueGeneration()
            assertEquals(
                VoiceRecognitionSessionCoordinator.StartResult.Started,
                coordinator.start(nextGeneration, {}, {}, {}),
            )
            assertTrue(coordinator.cancel(nextGeneration))
        }

    @Test
    fun `second stop for the same session is stale`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = FakeRecorderControl()
            val coordinator = newCoordinator(gate, recorder)

            recorder.completeStart()
            val generation = coordinator.issueGeneration()
            assertEquals(
                VoiceRecognitionSessionCoordinator.StartResult.Started,
                coordinator.start(generation, {}, {}, {}),
            )

            val first = coordinator.stop(generation) {}
            assertTrue(first is VoiceRecognitionSessionCoordinator.StopResult.Captured)

            val second = coordinator.stop(generation) {}
            assertEquals(VoiceRecognitionSessionCoordinator.StopResult.Stale, second)
        }

    @Test
    fun `listener failure during start releases the gate and stops the recorder`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = FakeRecorderControl()
            val coordinator = newCoordinator(gate, recorder)

            recorder.completeStart()
            val generation = coordinator.issueGeneration()

            val result =
                coordinator.start(
                    generation = generation,
                    onReadyForSpeech = {},
                    onBeginningOfSpeech = { throw IllegalStateException("client died") },
                    onRms = {},
                )

            assertTrue(result is VoiceRecognitionSessionCoordinator.StartResult.Failed)
            assertTrue(recorder.cancelCalled)
            assertFalse(gate.isHeld())

            val stopResult = coordinator.stop(generation) {}
            assertEquals(VoiceRecognitionSessionCoordinator.StopResult.Stale, stopResult)
        }

    private class FakeRecorderControl : VoiceRecognitionSessionCoordinator.RecorderControl {
        private val startGate = CompletableDeferred<Unit>()

        var startSucceeds = true
        var startRequested = false
        var stopCalled = false
        var cancelCalled = false

        fun completeStart() {
            startGate.complete(Unit)
        }

        override suspend fun prepare() = Unit

        override suspend fun start(): Boolean {
            startRequested = true
            startGate.await()
            return startSucceeds
        }

        override fun stop(): FloatArray {
            stopCalled = true
            return floatArrayOf(0.1f, 0.2f)
        }

        override fun cancel() {
            cancelCalled = true
        }

        override suspend fun collectSamples() {
            awaitCancellation()
        }

        override suspend fun streamRms(onRms: (Float) -> Unit) {
            awaitCancellation()
        }
    }
}
