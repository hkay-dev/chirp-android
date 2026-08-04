package dev.chirpboard.app

import dev.chirpboard.app.core.transcription.LocalSpeechModelCatalog
import dev.chirpboard.app.core.transcription.LocalSpeechComputeBackend
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelInfo
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriptionSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectableStreamingTranscriberProviderTest {
    @Test
    fun `Sherpa selection delegates preview preparation`() =
        runTest {
            val selection = FakeSelectionStore(LocalSpeechModelId.PARAKEET_TDT_600M)
            val sherpa = FakeStreamingProvider()
            val provider = SelectableStreamingTranscriberProvider(selection, sherpa)

            assertTrue(provider.prepare())
            assertNull(provider.openSession())
            assertEquals(2, sherpa.prepareOrOpenCalls)
            assertEquals(0, sherpa.releaseCalls)
        }

    @Test
    fun `GGUF selection disables and releases Sherpa preview`() =
        runTest {
            val selection = FakeSelectionStore(LocalSpeechModelId.PARAKEET_CTC_110M_Q8)
            val sherpa = FakeStreamingProvider()
            val provider = SelectableStreamingTranscriberProvider(selection, sherpa)

            assertFalse(provider.prepare())
            assertNull(provider.openSession())
            assertEquals(0, sherpa.prepareOrOpenCalls)
            assertEquals(2, sherpa.releaseCalls)
        }

    private class FakeSelectionStore(initial: LocalSpeechModelId) : LocalSpeechModelSelectionStore {
        private val selected = MutableStateFlow(initial)
        private val computeBackend = MutableStateFlow(LocalSpeechComputeBackend.CPU)
        override val availableModels: List<LocalSpeechModelInfo> = LocalSpeechModelCatalog.models
        override val selectedModel: StateFlow<LocalSpeechModelId> = selected
        override val selectedComputeBackend: StateFlow<LocalSpeechComputeBackend> = computeBackend

        override fun modelInfo(modelId: LocalSpeechModelId): LocalSpeechModelInfo =
            LocalSpeechModelCatalog.requireModel(modelId)

        override suspend fun selectModel(modelId: LocalSpeechModelId) {
            selected.value = modelId
        }

        override suspend fun selectComputeBackend(backend: LocalSpeechComputeBackend) {
            computeBackend.value = backend
        }
    }

    private class FakeStreamingProvider : StreamingTranscriberProvider {
        var prepareOrOpenCalls = 0
        var releaseCalls = 0

        override suspend fun prepare(): Boolean {
            prepareOrOpenCalls += 1
            return true
        }

        override suspend fun openSession(sampleRate: Int): StreamingTranscriptionSession? {
            prepareOrOpenCalls += 1
            return null
        }

        override suspend fun release() {
            releaseCalls += 1
        }
    }
}
