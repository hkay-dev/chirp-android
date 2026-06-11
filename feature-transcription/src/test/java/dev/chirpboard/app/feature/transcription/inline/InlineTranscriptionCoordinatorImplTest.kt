package dev.chirpboard.app.feature.transcription.inline

import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.modelreadiness.ModelReadyResult
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class InlineTranscriptionCoordinatorImplTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var transcriberProvider: TranscriberProvider
    private lateinit var textEnhancement: RecordingTextEnhancementPort
    private lateinit var readinessGate: SpeechModelReadinessGate
    private lateinit var coordinator: InlineTranscriptionCoordinatorImpl

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        transcriberProvider = mockk()
        textEnhancement = mockk(relaxed = true)
        readinessGate = mockk(relaxed = true)
        coordinator = InlineTranscriptionCoordinatorImpl(transcriberProvider, textEnhancement, readinessGate)

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "inline-test"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkObject(ReliabilityEventLogger)
        Dispatchers.resetMain()
    }

    @Test
    fun `keyboard dictation readiness path asks gate before initializing model`() = runTest {
        every { transcriberProvider.isReady() } returns false andThen false andThen true
        every { transcriberProvider.isModelDownloaded() } returns true
        coEvery { readinessGate.ensureReady(VerificationTrigger.KEYBOARD_DICTATION) } returns
            ModelReadyResult.Ready(ModelReadinessVerificationSource.PROCESS_CACHE)
        coEvery { transcriberProvider.initialize() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello")

        coordinator.transcribe(
            request =
                InlineTranscriptionRequest(
                    samples = floatArrayOf(0.1f, 0.2f),
                    llmEnabled = false,
                    processingModeId = "proofread",
                ),
            persistence = CapturingPersistence(),
            commitText = {},
        )
        coVerify { readinessGate.ensureReady(VerificationTrigger.KEYBOARD_DICTATION) }
    }

    @Test
    fun `file backed dictation is transcribed in bounded chunks`() = runTest {
        val sampleCount = 480_000 + 4_000
        val file = temporaryFolder.newFile("long-dictation.f32pcm")
        writeFloatPcm(file, sampleCount)
        every { transcriberProvider.isReady() } returns true
        var calls = 0
        coEvery { transcriberProvider.transcribe(any(), any()) } coAnswers {
            calls++
            TranscriptionOutcome.Success("chunk$calls")
        }

        var committed = ""
        coordinator.transcribe(
            request =
                InlineTranscriptionRequest(
                    samples = FloatArray(0),
                    llmEnabled = false,
                    processingModeId = "proofread",
                    audioSource =
                        InlineAudioSource.PcmFloatFile(
                            path = file.absolutePath,
                            sampleCount = sampleCount.toLong(),
                        ),
                ),
            persistence = CapturingPersistence(),
            commitText = { committed = it },
        )
        assertEquals("chunk1 chunk2 ", committed)
        assertEquals(2, calls)
    }

    @Test
    fun `refused commit persists transcript as rescue entry instead of dropping it`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello world")
        val persistence = CapturingPersistence()
        var reportedError: String? = null

        coordinator.transcribeWithCommitResult(
            request =
                InlineTranscriptionRequest(
                    samples = floatArrayOf(0.1f, 0.2f),
                    llmEnabled = false,
                    processingModeId = "proofread",
                ),
            persistence = persistence,
            commitText = { false },
            onRecordingError = { reportedError = it },
        )

        assertEquals("hello world", persistence.lastRawText)
        assertEquals(COMMIT_REFUSED_MESSAGE, persistence.lastErrorMessage)
        assertEquals(InlineCapturePersistReason.RESCUE, persistence.lastReason)
        assertEquals(COMMIT_REFUSED_MESSAGE, reportedError)
        assertEquals(InlineTranscriptionPhase.Error(COMMIT_REFUSED_MESSAGE), coordinator.phase.value)
    }

    @Test
    fun `accepted commit persists transcript without an error message`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello world")
        val persistence = CapturingPersistence()
        var committed = ""
        var completed = false

        coordinator.transcribeWithCommitResult(
            request =
                InlineTranscriptionRequest(
                    samples = floatArrayOf(0.1f, 0.2f),
                    llmEnabled = false,
                    processingModeId = "proofread",
                ),
            persistence = persistence,
            commitText = { text ->
                committed = text
                true
            },
            onRecordingCompleted = { completed = true },
        )

        assertEquals("hello world ", committed)
        assertEquals(true, completed)
        assertEquals("hello world", persistence.lastRawText)
        assertEquals(null, persistence.lastErrorMessage)
        assertEquals(InlineCapturePersistReason.COMPLETED, persistence.lastReason)
        assertEquals(InlineTranscriptionPhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `no speech discards exactly the request audio source and never the staged one`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.NoSpeech
        val persistence = CapturingPersistence()
        val source = InlineAudioSource.InMemory(floatArrayOf(0.1f, 0.2f))
        var completed = false

        coordinator.transcribeWithCommitResult(
            request =
                InlineTranscriptionRequest(
                    samples = floatArrayOf(0.1f, 0.2f),
                    llmEnabled = false,
                    processingModeId = "proofread",
                    audioSource = source,
                ),
            persistence = persistence,
            commitText = { true },
            onRecordingCompleted = { completed = true },
        )

        assertEquals(true, completed)
        // Identity-targeted discard: a detached pipeline resolving NoSpeech must never
        // fall back to discardSamples(), which could delete a newer dictation's staged
        // source instead of this request's own audio.
        assertSame(source, persistence.lastDiscardedSource)
        assertEquals(0, persistence.discardSamplesCalls)
        assertEquals(InlineTranscriptionPhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `marked user cancel persists capture as user cancelled`() = runTest {
        every { transcriberProvider.isReady() } returns true
        val transcribeStarted = CompletableDeferred<Unit>()
        coEvery { transcriberProvider.transcribe(any(), any()) } coAnswers {
            transcribeStarted.complete(Unit)
            awaitCancellation()
        }
        val persistence = CapturingPersistence()

        val job =
            launch {
                coordinator.transcribeWithCommitResult(
                    request = inMemoryRequest(),
                    persistence = persistence,
                    commitText = { true },
                )
            }
        transcribeStarted.await()
        coordinator.markUserCancelled()
        job.cancel()
        job.join()

        // An explicit cancel is not a rescue: the persistence layer must be told so it
        // can respect the saveKeyboardRecordings preference instead of retaining audio.
        assertEquals("Dictation cancelled", persistence.lastErrorMessage)
        assertEquals(InlineCapturePersistReason.USER_CANCELLED, persistence.lastReason)
    }

    @Test
    fun `unmarked cancellation rescues capture instead of dropping it`() = runTest {
        every { transcriberProvider.isReady() } returns true
        val transcribeStarted = CompletableDeferred<Unit>()
        coEvery { transcriberProvider.transcribe(any(), any()) } coAnswers {
            transcribeStarted.complete(Unit)
            awaitCancellation()
        }
        val persistence = CapturingPersistence()

        val job =
            launch {
                coordinator.transcribeWithCommitResult(
                    request = inMemoryRequest(),
                    persistence = persistence,
                    commitText = { true },
                )
            }
        transcribeStarted.await()
        // No markUserCancelled(): IME destruction, scope death or a system kill cancels
        // the pipeline without any user intent, so the capture must be force-rescued
        // (persisted even with saveKeyboardRecordings off) instead of discarded.
        job.cancel()
        job.join()

        assertEquals(CANCELLATION_RESCUE_MESSAGE, persistence.lastErrorMessage)
        assertEquals(InlineCapturePersistReason.RESCUE, persistence.lastReason)
    }

    @Test
    fun `stale user-cancel mark from a previous session does not misclassify a new request`() = runTest {
        every { transcriberProvider.isReady() } returns true
        val transcribeStarted = CompletableDeferred<Unit>()
        coEvery { transcriberProvider.transcribe(any(), any()) } coAnswers {
            transcribeStarted.complete(Unit)
            awaitCancellation()
        }
        val persistence = CapturingPersistence()

        // A cancel tap that never reached an in-flight pipeline leaves a mark behind.
        coordinator.markUserCancelled()

        val job =
            launch {
                coordinator.transcribeWithCommitResult(
                    request = inMemoryRequest(),
                    persistence = persistence,
                    commitText = { true },
                )
            }
        transcribeStarted.await()
        job.cancel()
        job.join()

        // The new request cleared the stale mark, so this interruption still rescues.
        assertEquals(InlineCapturePersistReason.RESCUE, persistence.lastReason)
    }

    @Test
    fun `cancellation after committed persist does not re-persist the capture`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello world")
        val persistStarted = CompletableDeferred<Unit>()
        val persistGate = CompletableDeferred<Unit>()
        val persistence =
            CapturingPersistence(
                onPersist = {
                    persistStarted.complete(Unit)
                    persistGate.await()
                },
            )

        val job =
            launch {
                coordinator.transcribeWithCommitResult(
                    request = inMemoryRequest(),
                    persistence = persistence,
                    commitText = { true },
                )
            }
        persistStarted.await()
        // Cancel while the committed-path persist is parked at a suspension point: the
        // CancellationException surfacing out of deliverTranscript must not write a
        // second entry for a capture whose transcript already reached the field.
        job.cancel()
        job.join()

        assertEquals(1, persistence.persistCalls)
        assertEquals(InlineCapturePersistReason.COMPLETED, persistence.lastReason)
    }

    private fun inMemoryRequest(): InlineTranscriptionRequest =
        InlineTranscriptionRequest(
            samples = floatArrayOf(0.1f, 0.2f),
            llmEnabled = false,
            processingModeId = "proofread",
        )

    private fun writeFloatPcm(
        file: java.io.File,
        sampleCount: Int,
    ) {
        file.outputStream().use { output ->
            val buffer = ByteBuffer.allocate(4_096 * java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
            var written = 0
            while (written < sampleCount) {
                buffer.clear()
                val count = minOf(4_096, sampleCount - written)
                repeat(count) { index ->
                    buffer.putFloat(((written + index) % 100) / 100f)
                }
                output.write(buffer.array(), 0, count * java.lang.Float.BYTES)
                written += count
            }
        }
    }

    private class CapturingPersistence(
        private val onPersist: suspend () -> Unit = {},
    ) : InlineCapturePersistence {
        var lastRawText: String? = null
        var lastProcessedText: String? = null
        var lastErrorMessage: String? = null
        var lastReason: InlineCapturePersistReason? = null
        var lastDiscardedSource: InlineAudioSource? = null
        var discardSamplesCalls = 0
        var persistCalls = 0

        override suspend fun persist(
            samples: FloatArray?,
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
            reason: InlineCapturePersistReason,
        ) {
            persistCalls++
            lastRawText = rawText
            lastProcessedText = processedText
            lastErrorMessage = errorMessage
            lastReason = reason
            onPersist()
        }

        override fun discardSamples() {
            discardSamplesCalls++
        }

        override fun discardAudioSource(audioSource: InlineAudioSource) {
            lastDiscardedSource = audioSource
        }
    }
}
