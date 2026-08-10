package dev.chirpboard.app.download

import androidx.work.Data
import androidx.work.WorkInfo
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadWork
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadWorkGatewayTest {
    private fun workInfo(
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
        tags: Set<String> = emptySet(),
    ): WorkInfo =
        mockk<WorkInfo> {
            every { this@mockk.state } returns state
            every { this@mockk.progress } returns progress
            every { this@mockk.outputData } returns output
            every { this@mockk.tags } returns tags
        }

    @Test
    fun `no work info maps to Idle`() {
        assertEquals(SpeechModelDownloadWork.Idle, mapDownloadWorkInfo(null))
    }

    @Test
    fun `cancelled maps to Idle so the status re-derives from disk`() {
        assertEquals(
            SpeechModelDownloadWork.Idle,
            mapDownloadWorkInfo(workInfo(WorkInfo.State.CANCELLED)),
        )
    }

    @Test
    fun `enqueued and blocked map to Waiting`() {
        assertEquals(
            SpeechModelDownloadWork.Waiting(),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.ENQUEUED)),
        )
        assertEquals(
            SpeechModelDownloadWork.Waiting(),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.BLOCKED)),
        )
    }

    @Test
    fun `the model id tag attributes work to its target model`() {
        val tags = setOf("some_other_tag", MODEL_ID_TAG_PREFIX + "parakeet_tdt_600m")
        assertEquals(
            SpeechModelDownloadWork.Waiting(modelId = LocalSpeechModelId.PARAKEET_TDT_600M),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.ENQUEUED, tags = tags)),
        )
        assertEquals(
            SpeechModelDownloadWork.Running(file = "", progress = 0f, modelId = LocalSpeechModelId.PARAKEET_TDT_600M),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.RUNNING, tags = tags)),
        )
        assertEquals(
            SpeechModelDownloadWork.Failed("Download failed", modelId = LocalSpeechModelId.PARAKEET_TDT_600M),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.FAILED, tags = tags)),
        )
    }

    @Test
    fun `a missing or unknown model tag maps to a null model id, not the default model`() {
        assertEquals(
            SpeechModelDownloadWork.Waiting(modelId = null),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.ENQUEUED)),
        )
        assertEquals(
            SpeechModelDownloadWork.Waiting(modelId = null),
            mapDownloadWorkInfo(
                workInfo(WorkInfo.State.ENQUEUED, tags = setOf(MODEL_ID_TAG_PREFIX + "not_a_model")),
            ),
        )
    }

    @Test
    fun `running carries file and progress fraction`() {
        val progress =
            Data
                .Builder()
                .putFloat(ModelDownloadWorker.PROGRESS_FRACTION, 0.42f)
                .putString(ModelDownloadWorker.PROGRESS_FILE, "encoder.int8.onnx")
                .build()

        assertEquals(
            SpeechModelDownloadWork.Running(file = "encoder.int8.onnx", progress = 0.42f),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.RUNNING, progress = progress)),
        )
    }

    @Test
    fun `running without progress data defaults to zero`() {
        assertEquals(
            SpeechModelDownloadWork.Running(file = "", progress = 0f),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.RUNNING)),
        )
    }

    @Test
    fun `failed surfaces the worker error message`() {
        val output =
            Data
                .Builder()
                .putString(ModelDownloadWorker.OUTPUT_ERROR, "No internet connection. Check your network and try again.")
                .build()

        assertEquals(
            SpeechModelDownloadWork.Failed("No internet connection. Check your network and try again."),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.FAILED, output = output)),
        )
    }

    @Test
    fun `failed without output data falls back to a generic message`() {
        assertEquals(
            SpeechModelDownloadWork.Failed("Download failed"),
            mapDownloadWorkInfo(workInfo(WorkInfo.State.FAILED)),
        )
    }

    @Test
    fun `succeeded maps to Succeeded`() {
        assertEquals(
            SpeechModelDownloadWork.Succeeded,
            mapDownloadWorkInfo(workInfo(WorkInfo.State.SUCCEEDED)),
        )
    }
}

class ModelDownloadWorkerRetryPolicyTest {
    @Test
    fun `retryable errors retry until the bounded attempt budget is spent`() {
        assertEquals(true, ModelDownloadWorker.shouldRetry(retryable = true, runAttemptCount = 0))
        assertEquals(true, ModelDownloadWorker.shouldRetry(retryable = true, runAttemptCount = 3))
        // Attempt 5 of 5: budget exhausted, fail terminally (ERR-3: no infinite retry loop).
        assertEquals(false, ModelDownloadWorker.shouldRetry(retryable = true, runAttemptCount = 4))
        assertEquals(false, ModelDownloadWorker.shouldRetry(retryable = true, runAttemptCount = 99))
    }

    @Test
    fun `non-retryable errors never retry`() {
        assertEquals(false, ModelDownloadWorker.shouldRetry(retryable = false, runAttemptCount = 0))
    }
}
