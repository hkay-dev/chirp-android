package dev.chirpboard.app.feature.transcription

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import dev.chirpboard.app.core.llm.LlmRuntimeSnapshot
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.llm.ResolvedProcessingModeSnapshot
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.EnhancementSubworkStatus
import dev.chirpboard.app.data.model.RecordingEnhancementExecutionSnapshot
import dev.chirpboard.app.data.model.RecordingEnhancementResult
import dev.chirpboard.app.data.model.RecordingEnhancementSnapshot
import dev.chirpboard.app.data.model.RecordingEnhancementSubworkState
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.Date
import java.util.UUID

class RecordingEnhancementWorkerTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var wordReplacementRepository: WordReplacementRepository
    private lateinit var wordReplacer: WordReplacer
    private lateinit var textEnhancement: FakeRecordingTextEnhancement
    private lateinit var foregroundUpdater: ForegroundUpdater
    private lateinit var completionExporter: TranscriptionCompletionExporter
    private lateinit var terminalNotificationDelivery: TerminalRecordingNotificationDelivery

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.getSystemService(NotificationManager::class.java) } returns mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        foregroundUpdater = mockk(relaxed = true)
        every { workerParams.foregroundUpdater } returns foregroundUpdater
        every { workerParams.runAttemptCount } returns 0
        every { foregroundUpdater.setForegroundAsync(any(), any(), any()) } answers {
            SettableFuture.create<Void?>().apply { set(null) }
        }
        recordingRepository = mockk(relaxed = true)
        wordReplacementRepository = mockk(relaxed = true)
        wordReplacer = mockk(relaxed = true)
        textEnhancement = FakeRecordingTextEnhancement()
        completionExporter = mockk(relaxed = true)
        terminalNotificationDelivery = mockk(relaxed = true)
        coEvery { recordingRepository.failEnhancement(any(), any(), any()) } returns true

        mockkObject(ReliabilityEventLogger)
        mockkStatic("dev.chirpboard.app.feature.transcription.TranscriptionWorkerSupportKt")
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "test-corr-id"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs
        every { buildEnhancementForegroundInfo(any()) } returns
            ForegroundInfo(
                ENHANCEMENT_FOREGROUND_NOTIFICATION_ID,
                mockk(relaxed = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        every { showTranscriptionCleanupRetryNotification(any(), any()) } just runs
        every { showTranscriptionReadyNotification(any(), any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkStatic("dev.chirpboard.app.feature.transcription.TranscriptionWorkerSupportKt")
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `disabled LLM finishes with the saved raw transcript`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            coEvery { recordingRepository.skipEnhancement(recordingId, EXECUTION_TOKEN) } returns true
            textEnhancement.enabled = false

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            coVerify(exactly = 1) { recordingRepository.skipEnhancement(recordingId, EXECUTION_TOKEN) }
            coVerify(exactly = 1) { completionExporter.exportIfCompleted(recordingId) }
            coVerify(exactly = 0) { recordingRepository.failEnhancement(any(), any(), any()) }
            assertEquals(0, textEnhancement.titleCalls)
            assertEquals(0, textEnhancement.summaryCalls)
        }

    @Test
    fun `temporary token refresh failure reparks the same token and retries`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            coEvery {
                recordingRepository.reparkEnhancementExecution(
                    recordingId,
                    EXECUTION_TOKEN,
                    "token refresh unavailable",
                )
            } returns true
            textEnhancement.availabilityFailure = IOException("token refresh unavailable")

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            coVerify(exactly = 1) {
                recordingRepository.reparkEnhancementExecution(
                    recordingId,
                    EXECUTION_TOKEN,
                    "token refresh unavailable",
                )
            }
            coVerify(exactly = 0) { recordingRepository.failEnhancement(any(), any(), any()) }
            coVerify(exactly = 0) { recordingRepository.completeEnhancement(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `temporary generation failure reparks the same token and retries`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            coEvery {
                recordingRepository.reparkEnhancementExecution(
                    recordingId,
                    EXECUTION_TOKEN,
                    "Vertex generation is temporarily unavailable",
                )
            } returns true
            textEnhancement.available = true
            textEnhancement.titleResult = Result.failure(IOException("Vertex generation is temporarily unavailable"))

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            coVerify(exactly = 1) {
                recordingRepository.reparkEnhancementExecution(
                    recordingId,
                    EXECUTION_TOKEN,
                    "Vertex generation is temporarily unavailable",
                )
            }
            coVerify(exactly = 0) { recordingRepository.failEnhancement(any(), any(), any()) }
            coVerify(exactly = 0) { recordingRepository.completeEnhancement(any(), any(), any(), any(), any()) }
            assertEquals(0, textEnhancement.summaryCalls)
        }

    @Test
    fun `temporary generation failure becomes terminal after the retry budget`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            every { workerParams.runAttemptCount } returns RecordingEnhancementWorker.MAX_RUN_ATTEMPTS - 1
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            textEnhancement.available = true
            textEnhancement.titleResult = Result.failure(IOException("Vertex generation is temporarily unavailable"))

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            coVerify(exactly = 0) { recordingRepository.reparkEnhancementExecution(any(), any(), any()) }
            coVerify(exactly = 1) {
                recordingRepository.failEnhancement(
                    recordingId,
                    EXECUTION_TOKEN,
                    "Vertex generation is temporarily unavailable",
                )
            }
        }

    @Test
    fun `unavailable cleanup posts a saved transcript retry notification`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns
                snapshot(
                    recordingId,
                    notifyWhenReady = true,
                    terminalNotificationPending = true,
                )
            textEnhancement.available = false

            worker().doWork()

            coVerify(exactly = 1) { terminalNotificationDelivery.deliverRequested(recordingId) }
        }

    @Test
    fun `cleared pending marker does not replay from the immutable preference`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns
                snapshot(
                    recordingId,
                    notifyWhenReady = true,
                    terminalNotificationPending = false,
                )
            textEnhancement.available = false

            worker().doWork()

            coVerify(exactly = 0) { terminalNotificationDelivery.deliverRequested(any()) }
        }

    @Test
    fun `all failed LLM operations preserve transcript and complete enhancement`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val resultSlot = slot<RecordingEnhancementResult>()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            coEvery { recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), any()) } returns true
            textEnhancement.available = true
            textEnhancement.titleResult = Result.failure(IllegalStateException("title failed"))
            textEnhancement.summaryResult = Result.failure(IllegalStateException("summary failed"))

            worker().doWork()

            assertEquals(listOf("processed transcript", "processed transcript"), textEnhancement.contextTexts)
            coVerify(exactly = 1) {
                recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), capture(resultSlot))
            }
            assertNull(resultSlot.captured.processedText)
            assertNull(resultSlot.captured.processingMode)
            assertNull(resultSlot.captured.title)
            assertNull(resultSlot.captured.summary)
            assertEquals(EnhancementSubworkStatus.FAILED, resultSlot.captured.titleStatus)
            assertEquals(EnhancementSubworkStatus.FAILED, resultSlot.captured.summaryStatus)
            coVerify(exactly = 0) { recordingRepository.reparkEnhancementExecution(any(), any(), any()) }
        }

    @Test
    fun `retry runs failed summary without rerunning succeeded title`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val resultSlot = slot<RecordingEnhancementResult>()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery {
                recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN)
            } returns snapshot(
                recordingId = recordingId,
                titleStatus = EnhancementSubworkStatus.SUCCEEDED,
                summaryStatus = EnhancementSubworkStatus.FAILED,
                summaryErrorMessage = "summary failed",
            )
            coEvery { recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), any()) } returns true
            textEnhancement.available = true
            textEnhancement.summaryResult = Result.success("Recovered summary")

            worker().doWork()

            coVerify(exactly = 1) {
                recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), capture(resultSlot))
            }
            assertEquals(0, textEnhancement.titleCalls)
            assertEquals(1, textEnhancement.summaryCalls)
            assertNull(resultSlot.captured.title)
            assertNull(resultSlot.captured.titleStatus)
            assertEquals("Recovered summary", resultSlot.captured.summary)
            assertEquals(EnhancementSubworkStatus.SUCCEEDED, resultSlot.captured.summaryStatus)
        }

    @Test
    fun `generated title is sanitized before persisting`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val resultSlot = slot<RecordingEnhancementResult>()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            coEvery { recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), any()) } returns true
            textEnhancement.available = true
            textEnhancement.titleResult = Result.success("\"Weekly Sync\nNotes\"")
            textEnhancement.summaryResult = Result.success("  \"A short summary.\"  ")

            worker().doWork()

            coVerify(exactly = 1) {
                recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), capture(resultSlot))
            }
            assertEquals("Weekly Sync Notes", resultSlot.captured.title)
            assertEquals("A short summary.", resultSlot.captured.summary)
        }

    @Test
    fun `title that sanitizes to empty is recorded as failed subwork`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val resultSlot = slot<RecordingEnhancementResult>()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            coEvery { recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), any()) } returns true
            textEnhancement.available = true
            textEnhancement.titleResult = Result.success("\"\"")

            worker().doWork()

            coVerify(exactly = 1) {
                recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), capture(resultSlot))
            }
            assertNull(resultSlot.captured.title)
            assertEquals(EnhancementSubworkStatus.FAILED, resultSlot.captured.titleStatus)
        }

    @Test
    fun `committed enhancement triggers completion export`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            coEvery { recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), any()) } returns true
            textEnhancement.available = true

            worker().doWork()

            coVerify(exactly = 1) { completionExporter.exportIfCompleted(recordingId) }
        }

    @Test
    fun `stale enhancement commit does not export`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            coEvery { recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any(), any()) } returns false
            textEnhancement.available = true

            worker().doWork()

            coVerify(exactly = 0) { completionExporter.exportIfCompleted(any()) }
        }

    private fun worker(): RecordingEnhancementWorker =
        RecordingEnhancementWorker(
            appContext = context,
            workerParams = workerParams,
            recordingRepository = recordingRepository,
            wordReplacementRepository = wordReplacementRepository,
            wordReplacer = wordReplacer,
            textEnhancement = textEnhancement,
            completionExporter = completionExporter,
            terminalNotificationDelivery = terminalNotificationDelivery,
        )

    private fun inputData(recordingId: UUID): Data =
        Data
            .Builder()
            .putString(RecordingEnhancementWorkRequest.INPUT_RECORDING_ID, recordingId.toString())
            .putString(RecordingEnhancementWorkRequest.INPUT_CORRELATION_ID, "test-corr-id")
            .putString(RecordingEnhancementWorkRequest.INPUT_EXECUTION_TOKEN, EXECUTION_TOKEN)
            .build()

    private fun snapshot(
        recordingId: UUID,
        titleStatus: EnhancementSubworkStatus = EnhancementSubworkStatus.PENDING,
        titleErrorMessage: String? = null,
        summaryStatus: EnhancementSubworkStatus = EnhancementSubworkStatus.PENDING,
        summaryErrorMessage: String? = null,
        notifyWhenReady: Boolean = false,
        terminalNotificationPending: Boolean = false,
    ): RecordingEnhancementSnapshot =
        RecordingEnhancementSnapshot(
            recording =
                Recording(
                    id = recordingId,
                    title = "Original title",
                    audioPath = "",
                    status = RecordingStatus.ENHANCING,
                    source = RecordingSource.APP,
                    notifyWhenReady = notifyWhenReady,
                    terminalNotificationPending = terminalNotificationPending,
                ),
            transcript =
                Transcript(
                    recordingId = recordingId,
                    rawText = "raw transcript",
                    processedText = "processed transcript",
                    processingMode = "word_replacement",
                ),
            execution =
                RecordingEnhancementExecutionSnapshot(
                    recordingId = recordingId,
                    sourceTranscriptRevision = "raw transcript||",
                    sourceProcessedTextRevision = "word_replacement|processed transcript",
                    processingModeId = null,
                    processingModeLabel = null,
                    processingModeType = null,
                    processingModePrompt = null,
                    processingMode =
                        RecordingEnhancementSubworkState(
                            requested = false,
                            status = EnhancementSubworkStatus.SKIPPED,
                        ),
                    title =
                        RecordingEnhancementSubworkState(
                            requested = true,
                            status = titleStatus,
                            errorMessage = titleErrorMessage,
                        ),
                    summary =
                        RecordingEnhancementSubworkState(
                            requested = true,
                            status = summaryStatus,
                            errorMessage = summaryErrorMessage,
                        ),
                    llmProviderId = null,
                    llmModelId = null,
                    activeEnhancementExecutionToken = EXECUTION_TOKEN,
                    legacyRequiresResolution = false,
                    createdAt = Date(),
                    lastAttemptedAt = null,
                    lastErrorMessage = null,
                ),
        )

    private class FakeRecordingTextEnhancement : RecordingTextEnhancementPort {
        var enabled = true
        var available = true
        var availabilityFailure: Exception? = null
        var titleResult: Result<String> = Result.success("title")
        var summaryResult: Result<String> = Result.success("summary")
        var titleCalls = 0
        var summaryCalls = 0
        val contextTexts = mutableListOf<String>()

        override suspend fun isEnhancementEnabled(): Boolean = enabled

        override suspend fun isEnhancementAvailable(providerId: String?): Boolean {
            availabilityFailure?.let { throw it }
            return available
        }

        override suspend fun defaultAutoTitleEnabled(): Boolean = false

        override suspend fun defaultAutoSummaryEnabled(): Boolean = false

        override suspend fun process(
            text: String,
            processingModeId: String,
        ): Result<String> = Result.success(text)

        override suspend fun generateTitle(transcript: String): Result<String> {
            contextTexts += transcript
            titleCalls += 1
            return titleResult
        }

        override suspend fun generateSummary(transcript: String): Result<String> {
            contextTexts += transcript
            summaryCalls += 1
            return summaryResult
        }
    }

    private companion object {
        const val EXECUTION_TOKEN = "enhancement-token"
    }
}
