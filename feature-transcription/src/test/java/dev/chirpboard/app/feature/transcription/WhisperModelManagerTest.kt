package dev.chirpboard.app.feature.transcription

import app.cash.turbine.test
import dev.chirpboard.app.core.modelreadiness.ModelReadinessEvaluation
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadState
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WhisperModelManagerTest {
    private lateinit var speechModelStore: SpeechModelStore
    private lateinit var readinessGate: SpeechModelReadinessGate
    private lateinit var classUnderTest: WhisperModelManager

    @Before
    fun setup() {
        speechModelStore = mockk(relaxed = true)
        readinessGate = mockk(relaxed = true)
        coEvery { speechModelStore.evaluateReadiness() } returns
            ModelReadinessEvaluation(
                isReady = false,
                unavailableReason = ModelReadinessUnavailableReason.MISSING_MODEL_FILES,
            )
        classUnderTest = WhisperModelManager(speechModelStore, readinessGate)
    }

    @Test
    fun `deleteModel invalidates cache without warming deleted model`() = runTest {
        coEvery { speechModelStore.deleteModel() } returns true
        coEvery { speechModelStore.evaluateReadiness() } returns
            ModelReadinessEvaluation(
                isReady = false,
                unavailableReason = ModelReadinessUnavailableReason.MISSING_MODEL_FILES,
            )

        val result = classUnderTest.deleteModel()

        assertTrue(result)
        verify { speechModelStore.invalidateVerificationCache() }
        verify { readinessGate.invalidate() }
        verify(exactly = 0) { readinessGate.warmupIfNeeded(any()) }
    }

    @Test
    fun `downloadModel forwards progress and completion`() = runTest {
        coEvery { speechModelStore.downloadModel() } returns
            flowOf(
                SpeechModelDownloadState.Progress("encoder.int8.onnx", 0.5f),
                SpeechModelDownloadState.Complete,
            )

        classUnderTest.downloadModel()

        assertEquals(WhisperModelManager.ModelStatus.Ready, classUnderTest.modelStatus.value)
        verify { readinessGate.warmupIfNeeded(VerificationTrigger.MODEL_DOWNLOAD) }
    }

    @Test
    fun `downloadModel surfaces store errors`() = runTest {
        coEvery { speechModelStore.downloadModel() } returns
            flowOf(SpeechModelDownloadState.Error("Download failed"))

        classUnderTest.downloadModel()

        val status = classUnderTest.modelStatus.value
        assertTrue(status is WhisperModelManager.ModelStatus.Error)
        assertEquals("Download failed", (status as WhisperModelManager.ModelStatus.Error).message)
    }
}
