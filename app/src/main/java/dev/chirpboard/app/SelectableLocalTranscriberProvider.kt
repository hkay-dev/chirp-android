package dev.chirpboard.app

import android.content.Context
import android.util.Log
import dev.chirpboard.app.core.transcription.LocalSpeechModelActivationResult
import dev.chirpboard.app.core.transcription.LocalSpeechModelActivator
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelDeletionGuard
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.ContinuousAudioTranscriberPreference
import dev.chirpboard.app.core.transcription.PcmFloatFileTranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.di.SherpaRecognizerProvider
import dev.chirpboard.app.download.ModelDownloader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide router for the selected authoritative offline recognizer.
 *
 * A model switch is committed only after the target model is verified and its native session is
 * ready. The prior provider is released afterward through its lease-aware manager. If a decode was
 * already using it, that decode completes and performs the deferred release from its finally block.
 */
class SelectableLocalTranscriberProvider(
    context: Context,
    private val downloader: ModelDownloader,
    private val selectionStore: LocalSpeechModelSelectionStore,
) : TranscriberProvider,
    LocalSpeechModelActivator,
    LocalSpeechModelDeletionGuard,
    ContinuousAudioTranscriberPreference,
    PcmFloatFileTranscriberProvider {
    private val switchMutex = Mutex()
    private val sherpa = SherpaRecognizerProvider(context.applicationContext, downloader)
    private val gguf = GgufRecognizerProvider(downloader)

    override fun isReady(): Boolean = provider(selectionStore.selectedModel.value).isReady()

    override fun prefersContinuousAudio(): Boolean =
        provider(selectionStore.selectedModel.value) is ContinuousAudioTranscriberPreference

    override fun isModelDownloaded(): Boolean = downloader.isModelDownloaded(selectionStore.selectedModel.value)

    override suspend fun initialize(): Boolean = provider(selectionStore.selectedModel.value).initialize()

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome {
        val modelAtStart = selectionStore.selectedModel.value
        val activeProvider = provider(modelAtStart)
        return try {
            activeProvider.transcribe(samples, sampleRate)
        } finally {
            if (selectionStore.selectedModel.value != modelAtStart) {
                activeProvider.release()
            }
        }
    }

    override suspend fun transcribePcmFloatFile(
        path: String,
        sampleCount: Long,
        sampleRate: Int,
    ): TranscriptionOutcome? {
        val modelAtStart = selectionStore.selectedModel.value
        val activeProvider = provider(modelAtStart)
        val fileProvider = activeProvider as? PcmFloatFileTranscriberProvider ?: return null
        return try {
            fileProvider.transcribePcmFloatFile(path, sampleCount, sampleRate)
        } finally {
            if (selectionStore.selectedModel.value != modelAtStart) activeProvider.release()
        }
    }

    override suspend fun activate(modelId: LocalSpeechModelId): LocalSpeechModelActivationResult =
        switchMutex.withLock {
            val current = selectionStore.selectedModel.value
            if (current == modelId && provider(modelId).isReady()) {
                return@withLock LocalSpeechModelActivationResult.Activated
            }
            if (!downloader.isModelDownloaded(modelId)) {
                return@withLock LocalSpeechModelActivationResult.ModelNotDownloaded
            }

            val target = provider(modelId)
            if (!target.initialize()) {
                return@withLock LocalSpeechModelActivationResult.Failed("Could not load the selected speech model")
            }

            try {
                selectionStore.selectModel(modelId)
            } catch (error: Exception) {
                target.release()
                return@withLock LocalSpeechModelActivationResult.Failed(
                    error.message ?: "Could not save the selected speech model",
                )
            }

            Log.i(TAG, "Activated local speech model ${modelId.persistedValue}")
            if (current != modelId) provider(current).release()
            LocalSpeechModelActivationResult.Activated
        }

    override suspend fun release() {
        provider(selectionStore.selectedModel.value).release()
    }

    override suspend fun releaseForDeletion(modelId: LocalSpeechModelId): Boolean =
        switchMutex.withLock {
            when (modelId) {
                LocalSpeechModelId.PARAKEET_TDT_600M ->
                    RecognizerManager.releaseForModelSwitchIfUnused() != RecognizerReleaseDecision.IN_USE

                LocalSpeechModelId.PARAKEET_CTC_110M_Q8 ->
                    !GgufRecognizerManager.isResident() || GgufRecognizerManager.releaseIfUnused()
            }
        }

    private fun provider(modelId: LocalSpeechModelId): TranscriberProvider =
        when (modelId) {
            LocalSpeechModelId.PARAKEET_TDT_600M -> sherpa
            LocalSpeechModelId.PARAKEET_CTC_110M_Q8 -> gguf
        }

    private companion object {
        const val TAG = "SelectableTranscriber"
    }
}
