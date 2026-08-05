package dev.chirpboard.app

import dev.chirpboard.app.core.transcription.InlineAudioSource
import android.util.Log
import dev.chirpboard.app.core.recording.RecordingStateManager
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the MIC-018 frame-starvation watchdog. The endpointer is purely event-driven —
 * time only advances when amplitude frames arrive — so a capture whose reads stall
 * entirely (wedged Bluetooth route, HAL stall) feeds it nothing and neither terminal can
 * ever fire. The wall-clock watchdog must: fire for a starved session at the wall-clock
 * budget; never fire while frames keep flowing; stand down once the endpointer reached
 * its own terminal; and route through the generation-gated cancel so the terminal fires
 * exactly once even against a racing or duplicate firing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionCaptureStallWatchdogTest {
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

    @Test
    fun `starved capture fires at the wall-clock budget`() =
        runTest {
            val endpointer =
                recognizerSessionEndpointer(clientCompleteSilenceMs = null, clientMinimumLengthMs = null)
            // No frames ever arrive: the recorder's sample count never moves.
            val stalled = async { awaitRecognitionCaptureStall(endpointer) { 0L } }

            // Budget = max(10s default no-speech, 15s floor) = 15s; nothing fires early.
            advanceTimeBy(14_999L)
            runCurrent()
            assertFalse(stalled.isCompleted)

            advanceTimeBy(1L)
            runCurrent()
            assertTrue(stalled.isCompleted)
            assertTrue(stalled.await())
        }

    @Test
    fun `flowing frames never fire the watchdog`() =
        runTest {
            val endpointer =
                recognizerSessionEndpointer(clientCompleteSilenceMs = null, clientMinimumLengthMs = null)
            var sampleCount = 0L
            // Frames keep arriving (the count advances on every poll) with no terminal yet
            // — e.g. a long dictation in progress: the watchdog must keep waiting.
            val stalled = async { awaitRecognitionCaptureStall(endpointer) { sampleCount++ } }

            advanceTimeBy(120_000L)
            runCurrent()
            assertFalse(stalled.isCompleted)
            stalled.cancel()
        }

    @Test
    fun `endpointer terminal stands the watchdog down without firing`() =
        runTest {
            val endpointer =
                recognizerSessionEndpointer(clientCompleteSilenceMs = null, clientMinimumLengthMs = null)
            // Frames flowed (all silence) and the endpointer reached its own terminal.
            endpointer.onAmplitude(0.001f, 0L)
            assertEquals(
                SpeechEndpointer.Event.NO_SPEECH_TIMEOUT,
                endpointer.onAmplitude(0.001f, 10_000L),
            )

            val stalled = async { awaitRecognitionCaptureStall(endpointer) { 0L } }
            advanceUntilIdle()
            assertFalse(stalled.await())
        }

    @Test
    fun `stalled session aborts exactly once through the generation-gated cancel`() =
        runTest {
            val manager = RecordingStateManager()
            val gate = VoiceRecognitionCaptureGate(manager)
            val recorder = StarvedRecorderControl()
            val coordinator =
                VoiceRecognitionSessionCoordinator(
                    scope = this,
                    captureGate = gate,
                    recorder = recorder,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val generation = coordinator.issueGeneration()
            assertEquals(
                VoiceRecognitionSessionCoordinator.StartResult.Started,
                coordinator.start(generation, {}, {}, {}),
            )

            val endpointer =
                recognizerSessionEndpointer(clientCompleteSilenceMs = null, clientMinimumLengthMs = null)
            var terminals = 0
            // The surfaces' watchdog wiring: a stall routes into the generation-gated
            // cancel — the same path the endpointer's own no-speech terminal uses.
            launch {
                if (awaitRecognitionCaptureStall(endpointer) { 0L }) {
                    if (coordinator.cancel(generation)) {
                        terminals++
                    }
                }
            }
            advanceUntilIdle()

            assertEquals(1, terminals)
            assertTrue(recorder.cancelCalled)
            assertFalse(gate.isHeld())
            // A late duplicate firing (or a racing stop) is stale: exactly one terminal.
            assertFalse(coordinator.cancel(generation))
        }

    /** A capture that opens normally but never delivers a single frame (stalled HAL). */
    private class StarvedRecorderControl : VoiceRecognitionSessionCoordinator.RecorderControl {
        var cancelCalled = false

        override suspend fun prepare() = Unit

        override suspend fun start(): Boolean = true

        override fun stop(): InlineAudioSource = InlineAudioSource.InMemory(FloatArray(0))

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
