package dev.chirpboard.app.feature.keyboard.session

import android.content.Context
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModePort
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.quickcapture.QuickCaptureError
import dev.chirpboard.app.core.quickcapture.QuickCaptureStartResult
import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionPort
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.feature.keyboard.quickcapture.QuickCaptureSessionImpl
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardSessionCoordinatorTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var context: Context
    private lateinit var capture: QuickCaptureSessionImpl
    private lateinit var transcription: InlineTranscriptionPort
    private lateinit var persistence: RecordingPersistence
    private lateinit var transcriberProvider: TranscriberProvider
    private lateinit var recordingStateManager: RecordingStateManager
    private lateinit var keyboardPreferences: KeyboardPreferences
    private lateinit var modePort: ProcessingModePort
    private lateinit var pendingStopStore: KeyboardPendingStopStore

    private lateinit var captureErrorHandler: CapturingSlot<(QuickCaptureError) -> Unit>
    private lateinit var limitReachedHandler: CapturingSlot<() -> Unit>
    private lateinit var stoppingTimeoutHandler: CapturingSlot<suspend (RecordingState.Stopping) -> Unit>
    private val phaseFlow = MutableStateFlow<InlineTranscriptionPhase>(InlineTranscriptionPhase.Idle)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)

        context =
            mockk {
                every { getSystemService(any<String>()) } returns null
            }
        captureErrorHandler = slot()
        limitReachedHandler = slot()
        capture = mockk(relaxed = true)
        every { capture.onRecordingError = capture(captureErrorHandler) } just runs
        every { capture.onLimitReached = capture(limitReachedHandler) } just runs

        transcription =
            mockk {
                every { phase } returns phaseFlow
                every { resetPhase() } just runs
                every { setError(any()) } just runs
            }
        persistence = RecordingPersistence()
        transcriberProvider = mockk(relaxed = true)

        stoppingTimeoutHandler = slot()
        recordingStateManager = mockk(relaxed = true)
        every {
            recordingStateManager.setStoppingTimeoutHandler(
                RecordingOrigin.KEYBOARD,
                capture(stoppingTimeoutHandler),
            )
        } just runs

        keyboardPreferences =
            mockk {
                every { llmEnabled } returns flowOf(true)
                every { microphoneGain } returns flowOf(1f)
            }
        modePort =
            mockk {
                every { currentMode } returns flowOf(ProcessingMode.Proofread)
                every { selectableModes } returns flowOf(emptyList())
            }
        pendingStopStore =
            mockk {
                coEvery { clear() } just runs
            }
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun stopAndTranscribe_passesRealAudioByteSizeToStoppingTimeout() =
        runTest {
            stubSuccessfulCapture(sampleCount = 1_000_000L)
            val transcribeStarted = CountDownLatch(1)
            coEvery {
                transcription.transcribeWithCommitResult(any(), any(), any(), any(), any())
            } coAnswers {
                transcribeStarted.countDown()
            }
            val coordinator = buildCoordinator()

            coordinator.startRecording()
            assertTrue(coordinator.isRecordingActive())
            coordinator.stopAndTranscribe { true }

            // 1,000,000 float samples at 4 bytes each, not the hardcoded 0L of the old code.
            verify { recordingStateManager.startStoppingTimeout(fileSizeBytes = 4_000_000L) }
            assertTrue(transcribeStarted.await(5, TimeUnit.SECONDS))
        }

    @Test
    fun limitReached_commitsThroughCommitTextProvider() =
        runTest {
            stubSuccessfulCapture(sampleCount = 16_000L)
            val commitOutcome = CompletableDeferred<Boolean>()
            coEvery {
                transcription.transcribeWithCommitResult(any(), any(), any(), any(), any())
            } coAnswers {
                commitOutcome.complete(arg<(String) -> Boolean>(2).invoke("auto stop"))
            }
            val coordinator = buildCoordinator()
            var committedText: String? = null
            coordinator.commitTextProvider = {
                { text ->
                    committedText = text
                    true
                }
            }

            coordinator.startRecording()
            limitReachedHandler.captured.invoke()

            assertTrue(commitOutcome.await())
            assertEquals("auto stop", committedText)
            assertFalse(coordinator.isRecordingActive())
        }

    @Test
    fun limitReached_withoutInputSessionRoutesCommitIntoRescue() =
        runTest {
            stubSuccessfulCapture(sampleCount = 16_000L)
            val commitOutcome = CompletableDeferred<Boolean>()
            coEvery {
                transcription.transcribeWithCommitResult(any(), any(), any(), any(), any())
            } coAnswers {
                commitOutcome.complete(arg<(String) -> Boolean>(2).invoke("auto stop"))
            }
            buildCoordinator().startRecording()

            limitReachedHandler.captured.invoke()

            // No live input session: the commit must report failure so the transcript
            // takes the rescue persistence path instead of being silently dropped.
            assertFalse(commitOutcome.await())
        }

    @Test
    fun stoppingTimeoutRescue_keepsInFlightTranscriptionAndRecoversStateMachine() =
        runTest {
            stubSuccessfulCapture(sampleCount = 16_000L)
            val transcribeStarted = CompletableDeferred<Unit>()
            val transcribeGate = CompletableDeferred<Unit>()
            val jobFinished = CountDownLatch(1)
            coEvery {
                transcription.transcribeWithCommitResult(any(), any(), any(), any(), any())
            } coAnswers {
                transcribeStarted.complete(Unit)
                transcribeGate.await()
                arg<() -> Unit>(3).invoke()
                jobFinished.countDown()
            }
            val coordinator = buildCoordinator()
            coordinator.startRecording()
            coordinator.stopAndTranscribe { true }
            transcribeStarted.await()

            stoppingTimeoutHandler.captured.invoke(stoppingState())

            // The in-flight job survives; the state machine leaves STOPPING right away.
            verify(exactly = 1) { recordingStateManager.onRecordingCompleted(any()) }
            verify { transcription.setError(KeyboardSessionCoordinator.STOP_TIMEOUT_IN_PROGRESS_MESSAGE) }
            assertEquals(0, persistence.persistCalls)
            coVerify { pendingStopStore.clear() }

            // Once the detached job completes, its stale completion must not drive the
            // (already recovered) state machine a second time.
            transcribeGate.complete(Unit)
            assertTrue(jobFinished.await(5, TimeUnit.SECONDS))
            verify(exactly = 1) { recordingStateManager.onRecordingCompleted(any()) }
        }

    @Test
    fun stoppingTimeoutRescue_detachesJobSoNextStopCannotCancelIt() =
        runTest {
            stubSuccessfulCapture(sampleCount = 16_000L)
            val firstStarted = CompletableDeferred<Unit>()
            val firstGate = CompletableDeferred<Unit>()
            val firstFinished = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            var firstCancelled = false
            var transcribeCalls = 0
            coEvery {
                transcription.transcribeWithCommitResult(any(), any(), any(), any(), any())
            } coAnswers {
                transcribeCalls++
                if (transcribeCalls == 1) {
                    firstStarted.complete(Unit)
                    try {
                        firstGate.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        firstCancelled = true
                        throw e
                    } finally {
                        firstFinished.countDown()
                    }
                } else {
                    secondStarted.countDown()
                }
            }
            val coordinator = buildCoordinator()
            coordinator.startRecording()
            coordinator.stopAndTranscribe { true }
            firstStarted.await()

            stoppingTimeoutHandler.captured.invoke(stoppingState())

            // Detach hands audio-source ownership to the in-flight pipeline without
            // deleting its temp file.
            assertEquals(1, persistence.releasePendingCalls)

            // The next dictation's stop must not cancel the detached pipeline.
            coordinator.startRecording()
            coordinator.stopAndTranscribe { true }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertEquals(2, transcribeCalls)
            assertFalse(firstCancelled)

            firstGate.complete(Unit)
            assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
            assertFalse(firstCancelled)
        }

    @Test
    fun stoppingTimeoutRescue_withoutInFlightJobPersistsRescueEntry() =
        runTest {
            buildCoordinator()

            stoppingTimeoutHandler.captured.invoke(stoppingState())

            assertEquals(1, persistence.persistCalls)
            assertEquals(KeyboardSessionCoordinator.STOP_TIMEOUT_RESCUE_MESSAGE, persistence.lastErrorMessage)
            verify { recordingStateManager.onRecordingCompleted(any()) }
            verify { transcription.setError(KeyboardSessionCoordinator.STOP_TIMEOUT_RESCUE_MESSAGE) }
            coVerify { pendingStopStore.clear() }
        }

    @Test
    fun captureError_afterStopIsIgnored() =
        runTest {
            stubSuccessfulCapture(sampleCount = 16_000L)
            val transcribeGate = CompletableDeferred<Unit>()
            coEvery {
                transcription.transcribeWithCommitResult(any(), any(), any(), any(), any())
            } coAnswers {
                transcribeGate.await()
            }
            val coordinator = buildCoordinator()
            coordinator.startRecording()
            coordinator.stopAndTranscribe { true }

            captureErrorHandler.captured.invoke(QuickCaptureError("Microphone disconnected"))

            verify(exactly = 0) { recordingStateManager.onRecordingError(any(), any()) }
            verify(exactly = 0) { transcription.setError(any()) }
            transcribeGate.complete(Unit)
        }

    @Test
    fun captureError_whileRecordingReportsError() =
        runTest {
            stubSuccessfulCapture(sampleCount = 16_000L)
            val coordinator = buildCoordinator()
            coordinator.startRecording()

            captureErrorHandler.captured.invoke(QuickCaptureError("Microphone disconnected"))

            assertFalse(coordinator.isRecordingActive())
            verify { recordingStateManager.onRecordingError("Microphone disconnected") }
            verify { transcription.setError("Microphone disconnected") }
        }

    @Test
    fun destroy_clearsItsOwnStoppingTimeoutHandler() =
        runTest {
            val clearedHandler = slot<suspend (RecordingState.Stopping) -> Unit>()
            every {
                recordingStateManager.clearStoppingTimeoutHandler(
                    RecordingOrigin.KEYBOARD,
                    capture(clearedHandler),
                )
            } just runs
            val coordinator = buildCoordinator()

            coordinator.destroy()

            assertSame(stoppingTimeoutHandler.captured, clearedHandler.captured)
        }

    private fun stubSuccessfulCapture(sampleCount: Long) {
        coEvery { capture.start() } returns QuickCaptureStartResult.Success
        every { capture.stopAsAudioSource() } returns
            InlineAudioSource.PcmFloatFile(
                path = "/tmp/keyboard-test.f32pcm",
                sampleCount = sampleCount,
            )
    }

    private fun buildCoordinator(): KeyboardSessionCoordinator =
        KeyboardSessionCoordinator(
            tag = "KeyboardSessionCoordinatorTest",
            context = context,
            scope = scope,
            capture = capture,
            transcription = transcription,
            persistence = persistence,
            transcriberProvider = transcriberProvider,
            recordingStateManager = recordingStateManager,
            keyboardPreferences = keyboardPreferences,
            modePort = modePort,
            pendingStopStore = pendingStopStore,
        )

    private fun stoppingState(): RecordingState.Stopping = RecordingState.Stopping(origin = RecordingOrigin.KEYBOARD)

    private class RecordingPersistence : InlineCapturePersistence {
        var persistCalls = 0
        var lastErrorMessage: String? = null
        var releasePendingCalls = 0

        override fun releasePendingAudioSource() {
            releasePendingCalls++
        }

        override suspend fun persist(
            samples: FloatArray?,
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
        ) {
            persistCalls++
            lastErrorMessage = errorMessage
        }

        override fun discardSamples() = Unit
    }
}
