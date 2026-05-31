package dev.chirpboard.app.feature.transcription

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import dev.chirpboard.app.core.llm.LlmRuntimeSnapshot
import dev.chirpboard.app.core.llm.RecordingTextEnhancementContext
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
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

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.getSystemService(NotificationManager::class.java) } returns mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        foregroundUpdater = mockk(relaxed = true)
        every { workerParams.foregroundUpdater } returns foregroundUpdater
        every { foregroundUpdater.setForegroundAsync(any(), any(), any()) } answers {
            SettableFuture.create<Void?>().apply { set(null) }
        }
        recordingRepository = mockk(relaxed = true)
        wordReplacementRepository = mockk(relaxed = true)
        wordReplacer = mockk(relaxed = true)
        textEnhancement = FakeRecordingTextEnhancement()

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
    }

    @After
    fun tearDown() {
        unmockkStatic("dev.chirpboard.app.feature.transcription.TranscriptionWorkerSupportKt")
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `requested enhancement fails recoverably when LLM is disabled`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            textEnhancement.available = false

            worker().doWork()

            coVerify(exactly = 1) {
                recordingRepository.failEnhancement(recordingId, EXECUTION_TOKEN, "LLM credentials unavailable for queued enhancement")
            }
            assertEquals(0, textEnhancement.titleCalls)
            assertEquals(0, textEnhancement.summaryCalls)
        }

    @Test
    fun `all failed LLM operations preserve transcript and complete enhancement`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val resultSlot = slot<RecordingEnhancementResult>()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId, EXECUTION_TOKEN) } returns snapshot(recordingId)
            coEvery { recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any()) } returns true
            textEnhancement.available = true
            textEnhancement.titleResult = Result.failure(IllegalStateException("title failed"))
            textEnhancement.summaryResult = Result.failure(IllegalStateException("summary failed"))

            worker().doWork()

            assertEquals(listOf("processed transcript"), textEnhancement.contextTexts)
            coVerify(exactly = 1) {
                recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", capture(resultSlot))
            }
            assertNull(resultSlot.captured.processedText)
            assertNull(resultSlot.captured.processingMode)
            assertNull(resultSlot.captured.title)
            assertNull(resultSlot.captured.summary)
            assertEquals(EnhancementSubworkStatus.FAILED, resultSlot.captured.titleStatus)
            assertEquals(EnhancementSubworkStatus.FAILED, resultSlot.captured.summaryStatus)
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
            coEvery { recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", any()) } returns true
            textEnhancement.available = true
            textEnhancement.summaryResult = Result.success("Recovered summary")

            worker().doWork()

            coVerify(exactly = 1) {
                recordingRepository.completeEnhancement(recordingId, EXECUTION_TOKEN, "raw transcript||", capture(resultSlot))
            }
            assertEquals(0, textEnhancement.titleCalls)
            assertEquals(1, textEnhancement.summaryCalls)
            assertNull(resultSlot.captured.title)
            assertNull(resultSlot.captured.titleStatus)
            assertEquals("Recovered summary", resultSlot.captured.summary)
            assertEquals(EnhancementSubworkStatus.SUCCEEDED, resultSlot.captured.summaryStatus)
        }

    private fun worker(): RecordingEnhancementWorker =
        RecordingEnhancementWorker(
            appContext = context,
            workerParams = workerParams,
            recordingRepository = recordingRepository,
            wordReplacementRepository = wordReplacementRepository,
            wordReplacer = wordReplacer,
            textEnhancement = textEnhancement,
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
    ): RecordingEnhancementSnapshot =
        RecordingEnhancementSnapshot(
            recording =
                Recording(
                    id = recordingId,
                    title = "Original title",
                    audioPath = "",
                    status = RecordingStatus.ENHANCING,
                    source = RecordingSource.APP,
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
        var available = true
        var titleResult: Result<String> = Result.success("title")
        var summaryResult: Result<String> = Result.success("summary")
        var titleCalls = 0
        var summaryCalls = 0
        val contextTexts = mutableListOf<String>()

        override suspend fun isEnhancementAvailable(): Boolean = available

        override suspend fun isEnhancementAvailable(providerId: String?): Boolean = available

        override suspend fun defaultAutoTitleEnabled(): Boolean = false

        override suspend fun defaultAutoSummaryEnabled(): Boolean = false

        override suspend fun process(
            text: String,
            processingModeId: String,
        ): Result<String> = Result.success(text)

        override fun createContext(
            text: String,
            providerId: String?,
            modelId: String?,
        ): RecordingTextEnhancementContext {
            contextTexts += text
            return RecordingTextEnhancementContext(
                text = text,
                providerId = providerId,
                modelId = modelId,
            )
        }

        override suspend fun generateTitle(transcript: String): Result<String> {
            titleCalls += 1
            return titleResult
        }

        override suspend fun generateSummary(transcript: String): Result<String> {
            summaryCalls += 1
            return summaryResult
        }
    }

    private companion object {
        const val EXECUTION_TOKEN = "enhancement-token"
    }
}
