package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.Data
import androidx.work.WorkerParameters
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingEnhancementIntent
import dev.chirpboard.app.data.model.RecordingEnhancementResult
import dev.chirpboard.app.data.model.RecordingEnhancementSnapshot
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class RecordingEnhancementWorkerTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var wordReplacementRepository: WordReplacementRepository
    private lateinit var wordReplacer: WordReplacer
    private lateinit var textProcessor: TextProcessor
    private lateinit var modeRepository: ProcessingModeRepository
    private lateinit var llmClient: LlmClient
    private lateinit var llmPreferences: LlmPreferences

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        recordingRepository = mockk(relaxed = true)
        wordReplacementRepository = mockk(relaxed = true)
        wordReplacer = mockk(relaxed = true)
        textProcessor = mockk(relaxed = true)
        modeRepository = mockk(relaxed = true)
        llmClient = mockk(relaxed = true)
        llmPreferences = mockk(relaxed = true)

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "test-corr-id"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `requested enhancement skips and completes when LLM is disabled`() =
        runTest {
            val recordingId = UUID.randomUUID()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId) } returns snapshot(recordingId)
            coEvery { llmPreferences.getLlmEnabled() } returns false

            worker().doWork()

            coVerify(exactly = 1) { recordingRepository.skipEnhancement(recordingId) }
            coVerify(exactly = 0) { llmClient.generateTitle(any()) }
            coVerify(exactly = 0) { llmClient.generateSummary(any()) }
        }

    @Test
    fun `all failed LLM operations preserve transcript and complete enhancement`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val resultSlot = slot<RecordingEnhancementResult>()
            every { workerParams.inputData } returns inputData(recordingId)
            coEvery { recordingRepository.beginEnhancement(recordingId) } returns snapshot(recordingId)
            coEvery { llmPreferences.getLlmEnabled() } returns true
            every { llmPreferences.fetchApiKey() } returns "api-key"
            coEvery { llmClient.generateTitle(any()) } returns Result.failure(IllegalStateException("title failed"))
            coEvery { llmClient.generateSummary(any()) } returns Result.failure(IllegalStateException("summary failed"))

            worker().doWork()

            coVerify(exactly = 1) {
                recordingRepository.completeEnhancement(recordingId, capture(resultSlot))
            }
            assertNull(resultSlot.captured.processedText)
            assertNull(resultSlot.captured.processingMode)
            assertNull(resultSlot.captured.title)
            assertNull(resultSlot.captured.summary)
        }

    private fun worker(): RecordingEnhancementWorker =
        RecordingEnhancementWorker(
            appContext = context,
            workerParams = workerParams,
            recordingRepository = recordingRepository,
            wordReplacementRepository = wordReplacementRepository,
            wordReplacer = wordReplacer,
            textProcessor = textProcessor,
            modeRepository = modeRepository,
            llmClient = llmClient,
            llmPreferences = llmPreferences,
        )

    private fun inputData(recordingId: UUID): Data =
        Data
            .Builder()
            .putString(RecordingEnhancementWorkRequest.INPUT_RECORDING_ID, recordingId.toString())
            .putString(RecordingEnhancementWorkRequest.INPUT_CORRELATION_ID, "test-corr-id")
            .build()

    private fun snapshot(recordingId: UUID): RecordingEnhancementSnapshot =
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
            intent =
                RecordingEnhancementIntent(
                    processingModeId = null,
                    autoTitle = true,
                    autoSummary = true,
                ),
        )
}
