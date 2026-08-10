package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.modelreadiness.ModelReadinessEvaluation
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadGateway
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadWork
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The download itself moved into app-scoped WorkManager work behind
 * [SpeechModelDownloadGateway] (ERR-1); these tests pin the manager's role as a pure
 * observer/mapper of that work. The old suspend `downloadModel` collect tests were
 * intentionally replaced: starting a download is now fire-and-observe.
 */
class SpeechModelManagerTest {
    private lateinit var speechModelStore: SpeechModelStore
    private lateinit var readinessGate: SpeechModelReadinessGate
    private lateinit var downloadGateway: SpeechModelDownloadGateway
    private lateinit var gatewayWork: MutableStateFlow<SpeechModelDownloadWork>

    private val notReadyEvaluation =
        ModelReadinessEvaluation(
            isReady = false,
            unavailableReason = ModelReadinessUnavailableReason.MISSING_MODEL_FILES,
        )

    @Before
    fun setup() {
        speechModelStore = mockk(relaxed = true)
        readinessGate = mockk(relaxed = true)
        downloadGateway = mockk(relaxed = true)
        gatewayWork = MutableStateFlow(SpeechModelDownloadWork.Idle)
        every { downloadGateway.work } returns gatewayWork
        coEvery { speechModelStore.evaluateReadiness() } returns notReadyEvaluation
    }

    // The manager's background work (gateway collection + init refresh) runs on the test's
    // backgroundScope so each test stays deterministic: queued init work only executes when
    // the test explicitly advances the scheduler.
    private fun TestScope.createManager(): SpeechModelManager =
        SpeechModelManager(speechModelStore, readinessGate, downloadGateway, backgroundScope)

    @Test
    fun `deleteModel invalidates cache without warming deleted model`() = runTest {
        val manager = createManager()
        coEvery { speechModelStore.deleteModel() } returns true

        val result = manager.deleteModel()

        assertTrue(result)
        verify { speechModelStore.invalidateVerificationCache() }
        verify { readinessGate.invalidate() }
        verify(exactly = 0) { readinessGate.verifyIfNeeded(any()) }
    }

    @Test
    fun `requestDownload delegates to the gateway with the storage choice`() = runTest {
        val manager = createManager()

        manager.requestDownload(preferInternalStorage = true)

        verify { downloadGateway.startDownload(preferInternalStorage = true) }
        assertEquals(
            SpeechModelManager.ModelStatus.Downloading(0f),
            manager.modelStatus.value,
        )
    }

    @Test
    fun `cancelDownload delegates to the gateway`() = runTest {
        val manager = createManager()

        manager.cancelDownload()

        verify { downloadGateway.cancelDownload() }
    }

    @Test
    fun `running work maps to Downloading with file and progress`() = runTest {
        val manager = createManager()

        manager.applyDownloadWork(
            work = SpeechModelDownloadWork.Running(file = "encoder.int8.onnx", progress = 0.5f),
            previous = SpeechModelDownloadWork.Waiting(),
        )

        assertEquals(
            SpeechModelManager.ModelStatus.Downloading(0.5f, "encoder.int8.onnx"),
            manager.modelStatus.value,
        )
        assertEquals(0.5f, manager.downloadProgress.value, 0.001f)
    }

    @Test
    fun `work for a different model never touches this model's status`() = runTest {
        val manager = createManager()

        // Managed model defaults to LocalSpeechModelId.DEFAULT; this work belongs to 600M.
        manager.applyDownloadWork(
            work =
                SpeechModelDownloadWork.Running(
                    file = "encoder.int8.onnx",
                    progress = 0.5f,
                    modelId = LocalSpeechModelId.PARAKEET_TDT_600M,
                ),
            previous = SpeechModelDownloadWork.Idle,
        )

        assertEquals(SpeechModelManager.ModelStatus.NotDownloaded, manager.modelStatus.value)
    }

    @Test
    fun `requestDownload refuses while another model's download is active`() = runTest {
        val manager = createManager()
        gatewayWork.value =
            SpeechModelDownloadWork.Running(
                file = "encoder.int8.onnx",
                progress = 0.5f,
                modelId = LocalSpeechModelId.PARAKEET_TDT_600M,
            )

        manager.requestDownload()

        assertTrue(manager.modelStatus.value is SpeechModelManager.ModelStatus.Error)
        verify(exactly = 0) { downloadGateway.startDownload(any<Boolean>()) }
        verify(exactly = 0) { downloadGateway.startDownload(any(), any()) }
    }

    @Test
    fun `waiting work maps to WaitingForNetwork`() = runTest {
        val manager = createManager()

        manager.applyDownloadWork(
            work = SpeechModelDownloadWork.Waiting(),
            previous = SpeechModelDownloadWork.Idle,
        )

        assertEquals(SpeechModelManager.ModelStatus.WaitingForNetwork, manager.modelStatus.value)
    }

    @Test
    fun `failed work surfaces a persistent error`() = runTest {
        val manager = createManager()

        manager.applyDownloadWork(
            work = SpeechModelDownloadWork.Failed("No internet connection. Check your network and try again."),
            previous = SpeechModelDownloadWork.Running("encoder.int8.onnx", 0.8f),
        )

        assertEquals(
            SpeechModelManager.ModelStatus.Error("No internet connection. Check your network and try again."),
            manager.modelStatus.value,
        )
    }

    @Test
    fun `succeeded work re-evaluates readiness and reports Ready`() = runTest {
        val manager = createManager()
        coEvery { speechModelStore.evaluateReadiness() } returns ModelReadinessEvaluation(isReady = true)

        manager.applyDownloadWork(
            work = SpeechModelDownloadWork.Succeeded,
            previous = SpeechModelDownloadWork.Running("tokens.txt", 0.99f),
        )

        assertEquals(SpeechModelManager.ModelStatus.Ready, manager.modelStatus.value)
        assertEquals(0f, manager.downloadProgress.value, 0.001f)
    }

    @Test
    fun `cancelled work re-derives the status from disk`() = runTest {
        val manager = createManager()

        manager.applyDownloadWork(
            work = SpeechModelDownloadWork.Running("encoder.int8.onnx", 0.3f),
            previous = SpeechModelDownloadWork.Waiting(),
        )
        manager.applyDownloadWork(
            work = SpeechModelDownloadWork.Idle,
            previous = SpeechModelDownloadWork.Running("encoder.int8.onnx", 0.3f),
        )

        assertEquals(SpeechModelManager.ModelStatus.NotDownloaded, manager.modelStatus.value)
    }

    @Test
    fun `statusFor never resets an interrupted download to NotDownloaded`() = runTest {
        val manager = createManager()

        // ERR-1: a stale FAILED work item must keep the honest error visible after a
        // process restart instead of silently showing "Not downloaded".
        val status =
            manager.statusFor(
                evaluation = notReadyEvaluation,
                work = SpeechModelDownloadWork.Failed("The download was interrupted. Retrying will resume where it left off."),
            )

        assertTrue(status is SpeechModelManager.ModelStatus.Error)
    }

    @Test
    fun `statusFor prefers a ready model over stale terminal work`() = runTest {
        val manager = createManager()

        val status =
            manager.statusFor(
                evaluation = ModelReadinessEvaluation(isReady = true),
                work = SpeechModelDownloadWork.Failed("stale"),
            )

        assertEquals(SpeechModelManager.ModelStatus.Ready, status)
    }

    @Test
    fun `statusFor maps integrity mismatch to an error when no work is active`() = runTest {
        val manager = createManager()

        val status =
            manager.statusFor(
                evaluation =
                    ModelReadinessEvaluation(
                        isReady = false,
                        unavailableReason = ModelReadinessUnavailableReason.INTEGRITY_MISMATCH,
                    ),
                work = SpeechModelDownloadWork.Idle,
            )

        assertEquals(SpeechModelManager.ModelStatus.Error("Model integrity check failed"), status)
    }
}
