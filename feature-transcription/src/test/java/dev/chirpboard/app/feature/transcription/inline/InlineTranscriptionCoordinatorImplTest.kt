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
import dev.chirpboard.app.core.transcription.PcmFloatFileTranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.entity.WordReplacement
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.transcription.WordReplacer
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
    private lateinit var wordReplacementRepository: WordReplacementRepository
    private lateinit var coordinator: InlineTranscriptionCoordinatorImpl

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        transcriberProvider = mockk()
        textEnhancement = mockk(relaxed = true)
        readinessGate = mockk(relaxed = true)
        wordReplacementRepository = mockk()
        coEvery { wordReplacementRepository.getEnabledReplacements() } returns emptyList()
        coordinator =
            InlineTranscriptionCoordinatorImpl(
                transcriberProvider,
                textEnhancement,
                readinessGate,
                wordReplacementRepository,
                WordReplacer(),
            )

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
                InlineTranscriptionRequest.inMemory(
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
    fun `ordinary file backed dictation is transcribed as one continuous utterance`() = runTest {
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
                    audioSource =
                        InlineAudioSource.PcmFloatFile(
                            path = file.absolutePath,
                            sampleCount = sampleCount.toLong(),
                        ),
                    llmEnabled = false,
                    processingModeId = "proofread",
                ),
            persistence = CapturingPersistence(),
            commitText = { committed = it },
        )
        assertEquals("chunk1 ", committed)
        assertEquals(1, calls)
    }

    @Test
    fun `file capable backend bypasses the sample array copy`() = runTest {
        val sampleCount = 32_000
        val file = temporaryFolder.newFile("mapped-dictation.f32pcm")
        writeFloatPcm(file, sampleCount)
        var fileCalls = 0
        var arrayCalls = 0
        val fileProvider =
            object : TranscriberProvider, PcmFloatFileTranscriberProvider {
                override fun isReady(): Boolean = true

                override fun isModelDownloaded(): Boolean = true

                override suspend fun initialize(): Boolean = true

                override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome {
                    arrayCalls++
                    return TranscriptionOutcome.Success("array")
                }

                override suspend fun transcribePcmFloatFile(
                    path: String,
                    sampleCount: Long,
                    sampleRate: Int,
                ): TranscriptionOutcome {
                    assertEquals(file.absolutePath, path)
                    assertEquals(32_000L, sampleCount)
                    assertEquals(16_000, sampleRate)
                    fileCalls++
                    return TranscriptionOutcome.Success("mapped")
                }

                override suspend fun release() = Unit
            }
        coordinator =
            InlineTranscriptionCoordinatorImpl(
                fileProvider,
                textEnhancement,
                readinessGate,
                wordReplacementRepository,
                WordReplacer(),
            )

        var committed = ""
        coordinator.transcribe(
            request =
                InlineTranscriptionRequest(
                    audioSource = InlineAudioSource.PcmFloatFile(file.absolutePath, sampleCount.toLong()),
                    llmEnabled = false,
                    processingModeId = "proofread",
                ),
            persistence = CapturingPersistence(),
            commitText = { committed = it },
        )

        assertEquals("mapped ", committed)
        assertEquals(1, fileCalls)
        assertEquals(0, arrayCalls)
    }

    @Test
    fun `long file backed dictation uses bounded overlapping chunks`() = runTest {
        val sampleCount = 960_000 + 4_000
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
                    audioSource =
                        InlineAudioSource.PcmFloatFile(
                            path = file.absolutePath,
                            sampleCount = sampleCount.toLong(),
                        ),
                    llmEnabled = false,
                    processingModeId = "proofread",
                ),
            persistence = CapturingPersistence(),
            commitText = { committed = it },
        )
        assertEquals("chunk1 chunk2 chunk3 ", committed)
        assertEquals(3, calls)
    }

    @Test
    fun `failed continuous decode retries from overlapping chunks`() = runTest {
        val sampleCount = 480_000 + 4_000
        val file = temporaryFolder.newFile("continuous-retry.f32pcm")
        writeFloatPcm(file, sampleCount)
        every { transcriberProvider.isReady() } returns true
        var calls = 0
        coEvery { transcriberProvider.transcribe(any(), any()) } coAnswers {
            calls++
            if (calls == 1) {
                TranscriptionOutcome.EngineError("continuous decode failed", retryable = true)
            } else {
                TranscriptionOutcome.Success("backup${calls - 1}")
            }
        }

        var committed = ""
        coordinator.transcribe(
            request =
                InlineTranscriptionRequest(
                    audioSource =
                        InlineAudioSource.PcmFloatFile(
                            path = file.absolutePath,
                            sampleCount = sampleCount.toLong(),
                        ),
                    llmEnabled = false,
                    processingModeId = "proofread",
                ),
            persistence = CapturingPersistence(),
            commitText = { committed = it },
        )
        assertEquals("backup1 backup2 ", committed)
        assertEquals(3, calls)
    }

    @Test
    fun `refused commit persists transcript as rescue entry instead of dropping it`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello world")
        val persistence = CapturingPersistence()
        var reportedError: String? = null

        coordinator.transcribeWithCommitResult(
            request =
                InlineTranscriptionRequest.inMemory(
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
    fun `refused commit does not claim a save when rescue persistence fails`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello world")
        val persistence = CapturingPersistence { throw IllegalStateException("disk unavailable") }
        var reportedError: String? = null

        coordinator.transcribeWithCommitResult(
            request = inMemoryRequest(),
            persistence = persistence,
            commitText = { false },
            onRecordingError = { reportedError = it },
        )

        assertEquals(COMMIT_REFUSED_UNSAVED_MESSAGE, reportedError)
        assertEquals(InlineTranscriptionPhase.Error(COMMIT_REFUSED_UNSAVED_MESSAGE), coordinator.phase.value)
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
                InlineTranscriptionRequest.inMemory(
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
                    audioSource = source,
                    llmEnabled = false,
                    processingModeId = "proofread",
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
        // IME-16: NoSpeech resolves to its own terminal phase so surfaces can show a
        // gentle "didn't catch that" instead of a silent return to Idle.
        assertEquals(InlineTranscriptionPhase.NoSpeech, coordinator.phase.value)
    }

    @Test
    fun `noteNoSpeech marks the terminal no-speech phase`() = runTest {
        coordinator.noteNoSpeech()

        assertEquals(InlineTranscriptionPhase.NoSpeech, coordinator.phase.value)

        coordinator.resetPhase()

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

    @Test
    fun `word replacements apply to dictation before commit and persistence`() = runTest {
        // PLH-10: the user's correction dictionary applies to the flagship dictation surface.
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns
            TranscriptionOutcome.Success("deploy kubernetes now")
        coEvery { wordReplacementRepository.getEnabledReplacements() } returns
            listOf(WordReplacement(original = "kubernetes", replacement = "Kubernetes"))
        val persistence = CapturingPersistence()
        var committed = ""

        coordinator.transcribeWithCommitResult(
            request = inMemoryRequest(),
            persistence = persistence,
            commitText = { text ->
                committed = text
                true
            },
        )

        assertEquals("deploy Kubernetes now ", committed)
        assertEquals("deploy Kubernetes now", persistence.lastRawText)
    }

    @Test
    fun `word replacement lookup failure falls back to the unmodified transcript`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello world")
        coEvery { wordReplacementRepository.getEnabledReplacements() } throws IllegalStateException("db down")
        val persistence = CapturingPersistence()
        var committed = ""

        coordinator.transcribeWithCommitResult(
            request = inMemoryRequest(),
            persistence = persistence,
            commitText = { text ->
                committed = text
                true
            },
        )

        assertEquals("hello world ", committed)
        assertEquals(InlineCapturePersistReason.COMPLETED, persistence.lastReason)
    }

    @Test
    fun `llm polish success commits raw immediately and persists both versions`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("raw words")
        coEvery { textEnhancement.process("raw words", "proofread") } returns Result.success("Polished words.")
        val persistence = CapturingPersistence()
        var committed = ""

        coordinator.transcribeWithCommitResult(
            request = inMemoryRequest(llmEnabled = true),
            persistence = persistence,
            commitText = { text ->
                committed = text
                true
            },
        )

        assertEquals("raw words ", committed)
        assertEquals("raw words", persistence.lastRawText)
        assertEquals("Polished words.", persistence.lastProcessedText)
        assertEquals(InlineTranscriptionPhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `raw transcript reaches the target before slow AI cleanup finishes`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("raw now")
        val cleanupGate = CompletableDeferred<Unit>()
        coEvery { textEnhancement.process("raw now", "proofread") } coAnswers {
            cleanupGate.await()
            Result.success("Raw later.")
        }
        val committed = CompletableDeferred<String>()
        val persistence = CapturingPersistence()

        val job =
            launch {
                coordinator.transcribeWithCommitResult(
                    request = inMemoryRequest(llmEnabled = true),
                    persistence = persistence,
                    commitText = { text ->
                        committed.complete(text)
                        true
                    },
                )
            }

        assertEquals("raw now ", committed.await())
        assertEquals(0, persistence.persistCalls)
        cleanupGate.complete(Unit)
        job.join()
        assertEquals("Raw later.", persistence.lastProcessedText)
    }

    @Test
    fun `llm result that drops an opening commits raw while keeping both versions`() = runTest {
        val raw = "Please remember that we should ship the release today"
        val processed = "We should ship the release today."
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success(raw)
        coEvery { textEnhancement.process(raw, "proofread") } returns Result.success(processed)
        val persistence = CapturingPersistence()
        var committed = ""

        coordinator.transcribeWithCommitResult(
            request = inMemoryRequest(llmEnabled = true),
            persistence = persistence,
            commitText = { text ->
                committed = text
                true
            },
        )

        assertEquals("$raw ", committed)
        assertEquals(raw, persistence.lastRawText)
        assertEquals(processed, persistence.lastProcessedText)
        assertEquals(InlineTranscriptionPhase.LlmError(AI_CONTENT_GUARD_MESSAGE), coordinator.phase.value)
    }

    @Test
    fun `llm polish failure commits raw text with LlmError phase`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("raw words")
        coEvery { textEnhancement.process(any(), any()) } returns Result.failure(IllegalStateException("boom"))
        val persistence = CapturingPersistence()
        var committed = ""

        coordinator.transcribeWithCommitResult(
            request = inMemoryRequest(llmEnabled = true),
            persistence = persistence,
            commitText = { text ->
                committed = text
                true
            },
        )

        // The raw transcript is never dropped on polish failure.
        assertEquals("raw words ", committed)
        assertEquals("raw words", persistence.lastRawText)
        assertEquals(null, persistence.lastProcessedText)
        assertEquals(InlineTranscriptionPhase.LlmError("LLM failed: boom"), coordinator.phase.value)
    }

    @Test
    fun `llm polish timeout commits raw text with LlmError phase`() = runTest {
        // ERR-19 (intentional behavior change): a timeout now surfaces the same LlmError panel as
        // a polish failure instead of silently returning to Idle.
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("raw words")
        coEvery { textEnhancement.process(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(60_000L)
            Result.success("too late")
        }
        val persistence = CapturingPersistence()
        var committed = ""

        coordinator.transcribeWithCommitResult(
            request = inMemoryRequest(llmEnabled = true),
            persistence = persistence,
            commitText = { text ->
                committed = text
                true
            },
        )

        assertEquals("raw words ", committed)
        assertEquals(null, persistence.lastProcessedText)
        assertEquals(InlineTranscriptionPhase.LlmError(ENHANCEMENT_TIMEOUT_MESSAGE), coordinator.phase.value)
    }

    @Test
    fun `long in-memory dictation is transcribed in bounded chunks`() = runTest {
        // Captures beyond the single-utterance cap go through the same 30s chunking as the
        // file-backed path instead of one quadratic-memory utterance.
        every { transcriberProvider.isReady() } returns true
        var calls = 0
        coEvery { transcriberProvider.transcribe(any(), any()) } coAnswers {
            calls++
            TranscriptionOutcome.Success("chunk$calls")
        }
        var committed = ""

        coordinator.transcribeWithCommitResult(
            request =
                InlineTranscriptionRequest.inMemory(
                    // 60.5s at 16kHz: just over the 60s single-utterance cap.
                    samples = FloatArray(968_000),
                    llmEnabled = false,
                    processingModeId = "proofread",
                ),
            persistence = CapturingPersistence(),
            commitText = { text ->
                committed = text
                true
            },
        )

        assertEquals(3, calls)
        assertEquals("chunk1 chunk2 chunk3 ", committed)
    }

    @Test
    fun `rescued transcription failure tells the user the audio was saved`() = runTest {
        // ERR-25: rescue persistence is invisible unless the error says the capture survived.
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns
            TranscriptionOutcome.EngineError("decode failed")
        val persistence = CapturingPersistence()
        var reportedError: String? = null

        coordinator.transcribeWithCommitResult(
            request = inMemoryRequest(),
            persistence = persistence,
            commitText = { true },
            onRecordingError = { reportedError = it },
        )

        // The persisted rescue entry keeps the raw failure message...
        assertEquals("Transcription engine failed: decode failed", persistence.lastErrorMessage)
        assertEquals(InlineCapturePersistReason.RESCUE, persistence.lastReason)
        // ...while the user-facing error notes that the audio was saved.
        assertEquals("Transcription engine failed: decode failed$RESCUE_SAVED_SUFFIX", reportedError)
        assertEquals(
            InlineTranscriptionPhase.Error("Transcription engine failed: decode failed$RESCUE_SAVED_SUFFIX"),
            coordinator.phase.value,
        )
    }

    @Test
    fun `failed rescue does not tell the user the audio was saved`() = runTest {
        every { transcriberProvider.isReady() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns
            TranscriptionOutcome.EngineError("decode failed")
        val persistence = CapturingPersistence { throw IllegalStateException("database unavailable") }
        var reportedError: String? = null

        coordinator.transcribeWithCommitResult(
            request = inMemoryRequest(),
            persistence = persistence,
            commitText = { true },
            onRecordingError = { reportedError = it },
        )

        val expected = "Transcription engine failed: decode failed"
        assertEquals(expected, reportedError)
        assertEquals(InlineTranscriptionPhase.Error(expected), coordinator.phase.value)
    }

    private fun inMemoryRequest(llmEnabled: Boolean = false): InlineTranscriptionRequest =
        InlineTranscriptionRequest.inMemory(
            samples = floatArrayOf(0.1f, 0.2f),
            llmEnabled = llmEnabled,
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
