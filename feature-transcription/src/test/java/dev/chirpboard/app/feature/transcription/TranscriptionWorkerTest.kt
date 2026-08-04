package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.llm.LlmRuntimeSnapshot
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.core.transcription.CloudFileTranscriptionProvider
import dev.chirpboard.app.core.transcription.CloudFileTranscriptionRequest
import dev.chirpboard.app.core.transcription.ContinuousAudioTranscriberPreference
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS
import dev.chirpboard.app.core.transcription.PcmFloatFileTranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.WordReplacement
import dev.chirpboard.app.data.model.RecordingEnhancementIntent
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.transcription.audio.AudioDecoder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Worker-level tests for the transcription pipeline's integration point (TST-011): the
 * execution-token begin/commit ordering, stale-token aborts, word-replacement application
 * before commit, the enhancement enqueue decision matrix and the retry/terminal-failure
 * disposition. Pieces are pinned individually in TranscriptionWorkerSupportTest; these
 * tests pin the order they are wired in.
 */
class TranscriptionWorkerTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var wordReplacementRepository: WordReplacementRepository
    private lateinit var textEnhancement: FakeRecordingTextEnhancement
    private lateinit var transcriberProvider: TranscriberProvider
    private lateinit var cloudTranscriber: CloudFileTranscriptionProvider
    private lateinit var transcriptionRoutingStore: TranscriptionRoutingStore
    private lateinit var audioDecoder: AudioDecoder
    private lateinit var audioEncoder: AudioEncoder
    private lateinit var recordingStateManager: RecordingStateManager
    private lateinit var workScheduler: FakeTranscriptionWorkScheduler
    private lateinit var completionExporter: TranscriptionCompletionExporter
    private lateinit var terminalNotificationDelivery: TerminalRecordingNotificationDelivery
    private lateinit var foregroundUpdater: ForegroundUpdater

    private val recordingId: UUID = UUID.randomUUID()
    private lateinit var audioPath: String

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        foregroundUpdater = mockk(relaxed = true)
        every { workerParams.foregroundUpdater } returns foregroundUpdater
        every { foregroundUpdater.setForegroundAsync(any(), any(), any()) } answers {
            SettableFuture.create<Void?>().apply { set(null) }
        }
        every { workerParams.inputData } returns inputData(recordingId)
        every { workerParams.runAttemptCount } returns 0

        recordingRepository = mockk(relaxed = true)
        profileRepository = mockk()
        coEvery { profileRepository.getProfile(any()) } returns null
        wordReplacementRepository = mockk()
        coEvery { wordReplacementRepository.getEnabledReplacements() } returns emptyList()
        textEnhancement = FakeRecordingTextEnhancement()
        transcriberProvider = mockk()
        every { transcriberProvider.isModelDownloaded() } returns true
        every { transcriberProvider.isReady() } returns true
        cloudTranscriber = mockk()
        transcriptionRoutingStore = mockk(relaxed = true)
        audioDecoder = mockk()
        audioEncoder = mockk(relaxed = true)
        recordingStateManager = mockk()
        every { recordingStateManager.state } returns MutableStateFlow<RecordingState>(RecordingState.Idle)
        workScheduler = FakeTranscriptionWorkScheduler()
        completionExporter = mockk(relaxed = true)
        terminalNotificationDelivery = mockk(relaxed = true)
        coEvery { recordingRepository.failTranscriptionExecution(any(), any(), any(), any()) } returns true

        audioPath = temporaryFolder.newFile("capture.m4a").absolutePath
        every { audioDecoder.decodeAsFlow(audioPath) } returns flowOf(FloatArray(16_000))

        mockkObject(ReliabilityEventLogger)
        mockkStatic("dev.chirpboard.app.feature.transcription.TranscriptionWorkerSupportKt")
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "test-corr-id"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs
        every { buildTranscriptionForegroundInfo(any()) } returns
            ForegroundInfo(
                TRANSCRIPTION_FOREGROUND_NOTIFICATION_ID,
                mockk(relaxed = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        every { showTranscriptionErrorNotification(any(), any(), any()) } just runs
        every { showTranscriptionReadyNotification(any(), any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkStatic("dev.chirpboard.app.feature.transcription.TranscriptionWorkerSupportKt")
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `happy path claims ownership transcribes then commits with the same execution token`() =
        runTest {
            stubOwnedRecording()
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("hello world")
            val transcriptSlot = slot<Transcript>()
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = capture(transcriptSlot),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals("hello world", transcriptSlot.captured.rawText)
            // Token contract ordering: claim ownership BEFORE engine work, commit guarded by
            // the same token AFTER it.
            coVerifyOrder {
                recordingRepository.beginTranscriptionExecution(recordingId, EXECUTION_TOKEN)
                transcriberProvider.transcribe(any(), any())
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            }
        }

    @Test
    fun `word replacements apply to the committed processed text but never the raw transcript`() =
        runTest {
            stubOwnedRecording()
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("deploy kubernetes now")
            coEvery { wordReplacementRepository.getEnabledReplacements() } returns
                listOf(WordReplacement(original = "kubernetes", replacement = "Kubernetes"))
            val transcriptSlot = slot<Transcript>()
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = capture(transcriptSlot),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true

            worker().doWork()

            assertEquals("deploy kubernetes now", transcriptSlot.captured.rawText)
            assertEquals("deploy Kubernetes now", transcriptSlot.captured.processedText)
            assertEquals("word_replacement", transcriptSlot.captured.processingMode)
        }

    @Test
    fun `rejected ownership claim aborts without transcribing committing or failing the row`() =
        runTest {
            stubRecording(status = RecordingStatus.PENDING_TRANSCRIPTION)
            coEvery {
                recordingRepository.beginTranscriptionExecution(recordingId, EXECUTION_TOKEN)
            } returns null

            val result = worker().doWork()

            // A stale worker must step aside silently: success result (no retry storm),
            // zero engine work, zero writes against the new owner's row.
            assertTrue(result is ListenableWorker.Result.Success)
            coVerify(exactly = 0) { transcriberProvider.transcribe(any(), any()) }
            coVerify(exactly = 0) {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = any(),
                    enhancementExecutionToken = any(),
                )
            }
            coVerify(exactly = 0) {
                recordingRepository.failTranscriptionExecution(any(), any(), any(), any())
            }
            assertTrue(workScheduler.enhancements.isEmpty())
            coVerify(exactly = 0) { completionExporter.exportIfCompleted(any()) }
        }

    @Test
    fun `recording already in enhancement phase is skipped without claiming ownership`() =
        runTest {
            stubRecording(status = RecordingStatus.PENDING_ENHANCEMENT)

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            coVerify(exactly = 0) { recordingRepository.beginTranscriptionExecution(any(), any()) }
            coVerify(exactly = 0) { transcriberProvider.transcribe(any(), any()) }
        }

    @Test
    fun `stale commit suppresses enhancement enqueue and completion export`() =
        runTest {
            stubOwnedRecording(profileId = profileWithEnhancement().id)
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("hello world")
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = any(),
                    enhancementExecutionToken = any(),
                )
            } returns false

            val result = worker().doWork()

            // A commit rejected as stale means another owner took the row mid-run: the
            // worker must not hand off enhancement work or export for it.
            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue(workScheduler.enhancements.isEmpty())
            coVerify(exactly = 0) { completionExporter.exportIfCompleted(any()) }
        }

    @Test
    fun `profile enhancement request enqueues enhancement with the committed token and defers export`() =
        runTest {
            val profile = profileWithEnhancement()
            stubOwnedRecording(profileId = profile.id)
            coEvery { profileRepository.getProfile(profile.id) } returns profile
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("hello world")
            val intents = mutableListOf<RecordingEnhancementIntent?>()
            val enhancementTokens = mutableListOf<String?>()
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = captureNullable(intents),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = captureNullable(enhancementTokens),
                )
            } returns true

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            val intent = intents.single()
            assertEquals(true, intent?.autoTitle)
            assertEquals("proofread", intent?.processingModeId)
            // The enhancement worker is handed exactly the token the commit stamped on the
            // snapshot — anything else would be rejected as stale by beginEnhancement.
            assertEquals(1, workScheduler.enhancements.size)
            assertEquals(recordingId, workScheduler.enhancements.single().recordingId)
            assertEquals(enhancementTokens.single(), workScheduler.enhancements.single().executionToken)
            // Export happens when the enhancement resolves, not now (PLH-3/ERR-5: exactly once).
            coVerify(exactly = 0) { completionExporter.exportIfCompleted(any()) }
        }

    @Test
    fun `disabled master switch skips requested profile enhancement and exports immediately`() =
        runTest {
            val profile = profileWithEnhancement()
            stubOwnedRecording(profileId = profile.id)
            coEvery { profileRepository.getProfile(profile.id) } returns profile
            textEnhancement.enabled = false
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("hello world")
            val intents = mutableListOf<RecordingEnhancementIntent?>()
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = captureNullable(intents),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertNull(intents.single())
            assertTrue(workScheduler.enhancements.isEmpty())
            coVerify(exactly = 1) { completionExporter.exportIfCompleted(recordingId) }
        }

    @Test
    fun `enhancement commit stays serialized through WorkManager handoff`() =
        runTest {
            val profile = profileWithEnhancement()
            stubOwnedRecording(profileId = profile.id)
            coEvery { profileRepository.getProfile(profile.id) } returns profile
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("hello world")
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true
            val enqueueEntered = CountDownLatch(1)
            val releaseEnqueue = CountDownLatch(1)
            workScheduler.beforeEnhancementEnqueue = {
                enqueueEntered.countDown()
                check(releaseEnqueue.await(5, TimeUnit.SECONDS))
            }

            val workerRun = async(Dispatchers.Default) { worker().doWork() }
            assertTrue(enqueueEntered.await(5, TimeUnit.SECONDS))
            val competingGateEntered = CountDownLatch(1)
            val competitor =
                async(Dispatchers.Default) {
                    withSerializedQueueScheduling { competingGateEntered.countDown() }
                }
            try {
                assertFalse(competingGateEntered.await(200, TimeUnit.MILLISECONDS))
            } finally {
                releaseEnqueue.countDown()
            }

            assertTrue(workerRun.await() is ListenableWorker.Result.Success)
            assertTrue(competingGateEntered.await(5, TimeUnit.SECONDS))
            competitor.await()
        }

    @Test
    fun `profile with all enhancement work off overrides global defaults and exports immediately`() =
        runTest {
            val profile =
                Profile(
                    name = "Plain",
                    defaultProcessingMode = null,
                    autoTitle = false,
                    autoSummary = false,
                )
            stubOwnedRecording(profileId = profile.id)
            coEvery { profileRepository.getProfile(profile.id) } returns profile
            // Global defaults request work, but the recording's profile explicitly opts out.
            textEnhancement.autoTitleDefault = true
            textEnhancement.autoSummaryDefault = true
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("hello world")
            val intents = mutableListOf<RecordingEnhancementIntent?>()
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = captureNullable(intents),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true

            worker().doWork()

            assertNull(intents.single())
            assertTrue(workScheduler.enhancements.isEmpty())
            coVerify(exactly = 1) { completionExporter.exportIfCompleted(recordingId) }
        }

    @Test
    fun `global auto title default enqueues enhancement when the recording has no profile`() =
        runTest {
            stubOwnedRecording(profileId = null)
            textEnhancement.autoTitleDefault = true
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("hello world")
            val intents = mutableListOf<RecordingEnhancementIntent?>()
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = captureNullable(intents),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true

            worker().doWork()

            assertEquals(true, intents.single()?.autoTitle)
            assertEquals(false, intents.single()?.autoSummary)
            assertEquals(1, workScheduler.enhancements.size)
            coVerify(exactly = 0) { completionExporter.exportIfCompleted(any()) }
        }

    @Test
    fun `snapshotted cloud AI off request never falls back to live enhancement settings`() =
        runTest {
            stubOwnedRecording(
                transcriptionEngine = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
                enhancementRequestSnapshotted = true,
            )
            textEnhancement.autoTitleDefault = true
            textEnhancement.autoSummaryDefault = true
            coEvery { cloudTranscriber.transcribeFile(any()) } returns
                TranscriptionOutcome.Success("captured words")
            val intents = mutableListOf<RecordingEnhancementIntent?>()
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = captureNullable(intents),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true

            worker().doWork()

            assertNull(intents.single())
            assertEquals(0, textEnhancement.defaultAutoTitleCalls)
            assertEquals(0, textEnhancement.defaultAutoSummaryCalls)
            assertTrue(workScheduler.enhancements.isEmpty())
        }

    @Test
    fun `retryable engine error under the attempt budget retries and reparks the row as pending`() =
        runTest {
            stubOwnedRecording()
            every { workerParams.runAttemptCount } returns 0
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.EngineError("decoder hiccup", retryable = true)

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            coVerify(exactly = 1) {
                recordingRepository.failTranscriptionExecution(
                    recordingId,
                    EXECUTION_TOKEN,
                    RecordingStatus.PENDING_TRANSCRIPTION,
                    any(),
                )
            }
            // Retries are silent: no terminal-error notification mid-budget.
            verify(exactly = 0) { showTranscriptionErrorNotification(any(), any(), any()) }
        }

    @Test
    fun `retryable engine error at the attempt budget fails terminally with FAILED status and a notification`() =
        runTest {
            stubOwnedRecording()
            every { workerParams.runAttemptCount } returns TRANSCRIPTION_MAX_RETRY_COUNT
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.EngineError("decoder hiccup", retryable = true)

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            coVerify(exactly = 1) {
                recordingRepository.failTranscriptionExecution(
                    recordingId,
                    EXECUTION_TOKEN,
                    RecordingStatus.FAILED,
                    any(),
                )
            }
            verify(exactly = 1) { showTranscriptionErrorNotification(any(), recordingId, any()) }
        }

    @Test
    fun `non-retryable engine error fails terminally even on the first attempt`() =
        runTest {
            stubOwnedRecording()
            every { workerParams.runAttemptCount } returns 0
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.EngineError("model rejected input", retryable = false)

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            coVerify(exactly = 1) {
                recordingRepository.failTranscriptionExecution(
                    recordingId,
                    EXECUTION_TOKEN,
                    RecordingStatus.FAILED,
                    any(),
                )
            }
            verify(exactly = 1) { showTranscriptionErrorNotification(any(), recordingId, any()) }
        }

    @Test
    fun `stale terminal failure does not post another owners notification`() =
        runTest {
            stubOwnedRecording()
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.EngineError("model rejected input", retryable = false)
            coEvery {
                recordingRepository.failTranscriptionExecution(
                    recordingId,
                    EXECUTION_TOKEN,
                    RecordingStatus.FAILED,
                    any(),
                )
            } returns false

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            verify(exactly = 0) { showTranscriptionErrorNotification(any(), any(), any()) }
            coVerify(exactly = 0) { terminalNotificationDelivery.deliverRequested(any()) }
        }

    @Test
    fun `enhancement enqueue failure reparks the snapshot as recoverable pending enhancement`() =
        runTest {
            val profile = profileWithEnhancement()
            stubOwnedRecording(profileId = profile.id)
            coEvery { profileRepository.getProfile(profile.id) } returns profile
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("hello world")
            val enhancementTokens = mutableListOf<String?>()
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = captureNullable(enhancementTokens),
                )
            } returns true
            val throwingScheduler = mockk<TranscriptionWorkScheduler>()
            every { throwingScheduler.enqueueEnhancement(any(), any(), any()) } throws
                IllegalStateException("WorkManager unavailable")

            val result = worker(scheduler = throwingScheduler).doWork()

            // The transcript is already committed; a failed handoff must leave a
            // recoverable marker instead of failing the run (the reconciler re-attaches it).
            assertTrue(result is ListenableWorker.Result.Success)
            val errorSlot = slot<String>()
            coVerify(exactly = 1) {
                recordingRepository.claimEnhancementExecution(
                    recordingId = recordingId,
                    executionToken = enhancementTokens.single()!!,
                    status = RecordingStatus.PENDING_ENHANCEMENT,
                    errorMessage = capture(errorSlot),
                )
            }
            assertTrue(errorSlot.captured.startsWith(RECOVERABLE_QUEUE_HANDOFF_PREFIX))
        }

    @Test
    fun `missing audio file fails the execution under its token without engine work`() =
        runTest {
            stubOwnedRecording(audioPathOverride = "/nonexistent/capture.m4a")

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            coVerify(exactly = 1) {
                recordingRepository.failTranscriptionExecution(
                    recordingId,
                    EXECUTION_TOKEN,
                    RecordingStatus.FAILED,
                    any(),
                )
            }
            coVerify(exactly = 0) { transcriberProvider.transcribe(any(), any()) }
        }

    @Test
    fun `cloud engine bypasses the local model and decoder`() =
        runTest {
            stubOwnedRecording(transcriptionEngine = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)
            val requestSlot = slot<CloudFileTranscriptionRequest>()
            coEvery { cloudTranscriber.transcribeFile(capture(requestSlot)) } returns
                TranscriptionOutcome.Success("cloud transcript")
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(audioPath, requestSlot.captured.audioPath)
            assertEquals("audio/mp4", requestSlot.captured.mimeType)
            verify(exactly = 0) { transcriberProvider.isModelDownloaded() }
            coVerify(exactly = 0) { transcriberProvider.transcribe(any(), any()) }
            verify(exactly = 0) { audioDecoder.decodeAsFlow(any()) }
        }

    @Test
    fun `cloud recording over one hour is pinned to local fallback before transcription`() =
        runTest {
            stubOwnedRecording(
                transcriptionEngine = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
                durationMs = GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS + 1L,
            )
            coEvery {
                recordingRepository.rerouteTranscriptionEngineForExecution(
                    recordingId = recordingId,
                    executionToken = EXECUTION_TOKEN,
                    expectedEngineId = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id,
                    newEngineId = TranscriptionEngine.LOCAL_PARAKEET.id,
                )
            } returns true
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("long local transcript")
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            coVerify(exactly = 1) { transcriberProvider.transcribe(any(), any()) }
            coVerify(exactly = 0) { cloudTranscriber.transcribeFile(any()) }
        }

    @Test
    fun `cloud keyboard audio becomes the durable recording wav before upload`() =
        runTest {
            val rawAudio = temporaryFolder.newFile("keyboard-capture.f32pcm").apply {
                writeBytes(ByteArray(64))
            }
            stubOwnedRecording(
                audioPathOverride = rawAudio.absolutePath,
                transcriptionEngine = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
            )
            val encodedPath = slot<String>()
            every {
                audioEncoder.encodePcmFloatFile(
                    inputPath = rawAudio.absolutePath,
                    sampleCount = rawAudio.length() / Float.SIZE_BYTES,
                    sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                    outputPath = capture(encodedPath),
                    format = RecordingOutputFormat.WAV,
                    config = any(),
                )
            } answers {
                writeValidWav(encodedPath.captured)
                true
            }
            val swappedPath = slot<String>()
            coEvery {
                recordingRepository.swapAudioPathForTranscriptionExecution(
                    recordingId = recordingId,
                    executionToken = EXECUTION_TOKEN,
                    expectedAudioPath = rawAudio.absolutePath,
                    newAudioPath = capture(swappedPath),
                )
            } returns true
            val cloudRequest = slot<CloudFileTranscriptionRequest>()
            coEvery { cloudTranscriber.transcribeFile(capture(cloudRequest)) } returns
                TranscriptionOutcome.Success("cloud transcript")
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(encodedPath.captured, swappedPath.captured)
            assertEquals(swappedPath.captured, cloudRequest.captured.audioPath)
            assertEquals("audio/wav", cloudRequest.captured.mimeType)
            assertEquals(rawAudio.parentFile, File(swappedPath.captured).parentFile)
            assertTrue(File(swappedPath.captured).isFile)
            assertFalse(rawAudio.exists())
            coVerifyOrder {
                recordingRepository.swapAudioPathForTranscriptionExecution(
                    recordingId = recordingId,
                    executionToken = EXECUTION_TOKEN,
                    expectedAudioPath = rawAudio.absolutePath,
                    newAudioPath = swappedPath.captured,
                )
                cloudTranscriber.transcribeFile(any())
            }
        }

    @Test
    fun `rejected cloud audio path swap keeps raw audio and deletes the unowned wav`() =
        runTest {
            val rawAudio = temporaryFolder.newFile("stale-keyboard-capture.f32pcm").apply {
                writeBytes(ByteArray(64))
            }
            stubOwnedRecording(
                audioPathOverride = rawAudio.absolutePath,
                transcriptionEngine = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
            )
            val encodedPath = slot<String>()
            every {
                audioEncoder.encodePcmFloatFile(
                    inputPath = rawAudio.absolutePath,
                    sampleCount = rawAudio.length() / Float.SIZE_BYTES,
                    sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                    outputPath = capture(encodedPath),
                    format = RecordingOutputFormat.WAV,
                    config = any(),
                )
            } answers {
                writeValidWav(encodedPath.captured)
                true
            }
            coEvery {
                recordingRepository.swapAudioPathForTranscriptionExecution(
                    recordingId = recordingId,
                    executionToken = EXECUTION_TOKEN,
                    expectedAudioPath = rawAudio.absolutePath,
                    newAudioPath = any(),
                )
            } returns false

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue(rawAudio.isFile)
            assertFalse(File(encodedPath.captured).exists())
            coVerify(exactly = 0) { cloudTranscriber.transcribeFile(any()) }
            coVerify(exactly = 0) {
                recordingRepository.failTranscriptionExecution(any(), any(), any(), any())
            }
        }

    @Test
    fun `ready notification posts after a terminal transcript commit`() =
        runTest {
            stubOwnedRecording(
                notifyWhenReady = true,
                terminalNotificationPending = true,
            )
            coEvery { transcriberProvider.transcribe(any(), any()) } returns
                TranscriptionOutcome.Success("ready transcript")
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = null,
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = null,
                )
            } returns true

            worker().doWork()

            coVerify(exactly = 1) { terminalNotificationDelivery.deliverRequested(recordingId) }
        }

    @Test
    fun `continuous backend receives the complete recording in one call`() =
        runTest {
            val sampleCount = 31 * AudioDecoder.TARGET_SAMPLE_RATE
            every { audioDecoder.decodeAsFlow(audioPath) } returns flowOf(FloatArray(sampleCount))
            stubOwnedRecording(durationMs = 31_000L)
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true
            val receivedSizes = mutableListOf<Int>()
            val continuousProvider =
                object : TranscriberProvider, ContinuousAudioTranscriberPreference {
                    override fun prefersContinuousAudio(): Boolean = true

                    override fun isReady(): Boolean = true

                    override fun isModelDownloaded(): Boolean = true

                    override suspend fun initialize(): Boolean = true

                    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome {
                        receivedSizes += samples.size
                        return TranscriptionOutcome.Success("continuous transcript")
                    }

                    override suspend fun release() = Unit
                }

            val result = worker(provider = continuousProvider).doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(listOf(sampleCount), receivedSizes)
        }

    @Test
    fun `continuous backend maps raw float PCM directly`() =
        runTest {
            val sampleCount = AudioDecoder.TARGET_SAMPLE_RATE
            val rawFile = temporaryFolder.newFile("capture.f32pcm")
            rawFile.writeBytes(
                ByteBuffer
                    .allocate(sampleCount * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .array(),
            )
            stubOwnedRecording(audioPathOverride = rawFile.absolutePath, durationMs = 1_000L)
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true
            var fileCalls = 0
            var arrayCalls = 0
            val directProvider =
                object : TranscriberProvider, ContinuousAudioTranscriberPreference, PcmFloatFileTranscriberProvider {
                    override fun prefersContinuousAudio(): Boolean = true
                    override fun isReady(): Boolean = true
                    override fun isModelDownloaded(): Boolean = true
                    override suspend fun initialize(): Boolean = true
                    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome {
                        arrayCalls++
                        return TranscriptionOutcome.Success("array transcript")
                    }
                    override suspend fun transcribePcmFloatFile(
                        path: String,
                        sampleCount: Long,
                        sampleRate: Int,
                    ): TranscriptionOutcome {
                        assertEquals(rawFile.absolutePath, path)
                        assertEquals(16_000L, sampleCount)
                        fileCalls++
                        return TranscriptionOutcome.Success("mapped transcript")
                    }
                    override suspend fun release() = Unit
                }

            val result = worker(provider = directProvider).doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(1, fileCalls)
            assertEquals(0, arrayCalls)
        }

    @Test
    fun `continuous backend falls back to chunks past the native memory limit`() =
        runTest {
            val sampleCount = 31 * AudioDecoder.TARGET_SAMPLE_RATE
            every { audioDecoder.decodeAsFlow(audioPath) } returns flowOf(FloatArray(sampleCount))
            stubOwnedRecording(durationMs = 301_000L)
            coEvery {
                recordingRepository.commitTranscriptionResult(
                    transcript = any(),
                    timings = any(),
                    enhancementIntent = any(),
                    expectedExecutionToken = EXECUTION_TOKEN,
                    enhancementExecutionToken = any(),
                )
            } returns true
            val receivedSizes = mutableListOf<Int>()
            val continuousProvider =
                object : TranscriberProvider, ContinuousAudioTranscriberPreference {
                    override fun prefersContinuousAudio(): Boolean = true
                    override fun isReady(): Boolean = true
                    override fun isModelDownloaded(): Boolean = true
                    override suspend fun initialize(): Boolean = true
                    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome {
                        receivedSizes += samples.size
                        return TranscriptionOutcome.Success("chunk transcript")
                    }
                    override suspend fun release() = Unit
                }

            val result = worker(provider = continuousProvider).doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(2, receivedSizes.size)
        }

    private fun worker(
        scheduler: TranscriptionWorkScheduler = workScheduler,
        provider: TranscriberProvider = transcriberProvider,
    ): TranscriptionWorker =
        TranscriptionWorker(
            appContext = context,
            workerParams = workerParams,
            recordingRepository = recordingRepository,
            profileRepository = profileRepository,
            wordReplacementRepository = wordReplacementRepository,
            wordReplacer = WordReplacer(),
            textEnhancement = textEnhancement,
            transcriberProvider = provider,
            cloudTranscriber = cloudTranscriber,
            transcriptionRoutingStore = transcriptionRoutingStore,
            audioDecoder = audioDecoder,
            audioEncoder = audioEncoder,
            recordingStateManager = recordingStateManager,
            workScheduler = scheduler,
            completionExporter = completionExporter,
            terminalNotificationDelivery = terminalNotificationDelivery,
        )

    private fun stubRecording(
        status: RecordingStatus,
        profileId: UUID? = null,
        audioPathOverride: String? = null,
        transcriptionEngine: TranscriptionEngine = TranscriptionEngine.LOCAL_PARAKEET,
        notifyWhenReady: Boolean = false,
        terminalNotificationPending: Boolean = false,
        enhancementRequestSnapshotted: Boolean = false,
        durationMs: Long = 0L,
    ): Recording {
        val recording =
            Recording(
                id = recordingId,
                title = "Recording",
                audioPath = audioPathOverride ?: audioPath,
                status = status,
                source = RecordingSource.APP,
                profileId = profileId,
                transcriptionExecutionToken = EXECUTION_TOKEN,
                transcriptionEngineId = transcriptionEngine.id,
                notifyWhenReady = notifyWhenReady,
                terminalNotificationPending = terminalNotificationPending,
                enhancementRequestSnapshotted = enhancementRequestSnapshotted,
                durationMs = durationMs,
            )
        coEvery { recordingRepository.getRecording(recordingId) } returns recording
        return recording
    }

    /** Stubs a pending recording whose ownership claim succeeds for [EXECUTION_TOKEN]. */
    private fun stubOwnedRecording(
        profileId: UUID? = null,
        audioPathOverride: String? = null,
        transcriptionEngine: TranscriptionEngine = TranscriptionEngine.LOCAL_PARAKEET,
        notifyWhenReady: Boolean = false,
        terminalNotificationPending: Boolean = false,
        enhancementRequestSnapshotted: Boolean = false,
        durationMs: Long = 0L,
    ) {
        val recording =
            stubRecording(
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                profileId = profileId,
                audioPathOverride = audioPathOverride,
                transcriptionEngine = transcriptionEngine,
                notifyWhenReady = notifyWhenReady,
                terminalNotificationPending = terminalNotificationPending,
                enhancementRequestSnapshotted = enhancementRequestSnapshotted,
                durationMs = durationMs,
            )
        coEvery {
            recordingRepository.beginTranscriptionExecution(recordingId, EXECUTION_TOKEN)
        } returns recording.copy(status = RecordingStatus.TRANSCRIBING)
    }

    private fun profileWithEnhancement(): Profile =
        Profile(
            name = "Meetings",
            defaultProcessingMode = "proofread",
            autoTitle = true,
            autoSummary = false,
        )

    private fun inputData(recordingId: UUID): Data =
        Data
            .Builder()
            .putString(TranscriptionWorker.INPUT_RECORDING_ID, recordingId.toString())
            .putString(TranscriptionWorkRequest.INPUT_CORRELATION_ID, "test-corr-id")
            .putString(TranscriptionWorkRequest.INPUT_EXECUTION_TOKEN, EXECUTION_TOKEN)
            .build()

    private fun writeValidWav(path: String) {
        WavFileWriter(File(path), AudioDecoder.TARGET_SAMPLE_RATE).use { writer ->
            writer.appendPcm16(ByteArray(64), 64)
        }
    }

    private class FakeRecordingTextEnhancement : RecordingTextEnhancementPort {
        var enabled = true
        var autoTitleDefault = false
        var autoSummaryDefault = false
        var defaultAutoTitleCalls = 0
        var defaultAutoSummaryCalls = 0

        override suspend fun isEnhancementEnabled(): Boolean = enabled

        override suspend fun isEnhancementAvailable(providerId: String?): Boolean = true

        override suspend fun defaultAutoTitleEnabled(): Boolean {
            defaultAutoTitleCalls += 1
            return autoTitleDefault
        }

        override suspend fun defaultAutoSummaryEnabled(): Boolean {
            defaultAutoSummaryCalls += 1
            return autoSummaryDefault
        }

        override suspend fun runtimeSnapshot(): LlmRuntimeSnapshot =
            LlmRuntimeSnapshot(providerId = "gemini", modelId = "gemini-test")

        override suspend fun process(
            text: String,
            processingModeId: String,
        ): Result<String> = Result.success(text)

        override suspend fun generateTitle(transcript: String): Result<String> = Result.success("title")

        override suspend fun generateSummary(transcript: String): Result<String> = Result.success("summary")
    }

    private companion object {
        const val EXECUTION_TOKEN = "transcription-token"
    }
}
