package dev.chirpboard.app

import android.content.Context
import android.util.Log
import dev.chirpboard.app.core.transcription.LocalSpeechModelActivationResult
import dev.chirpboard.app.core.transcription.LocalSpeechModelActivator
import dev.chirpboard.app.core.transcription.LocalSpeechComputeBackend
import dev.chirpboard.app.core.transcription.LocalSpeechComputeBackendActivationResult
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.ContinuousAudioTranscriberPreference
import dev.chirpboard.app.core.transcription.PcmFloatFileTranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.di.SherpaRecognizerProvider
import dev.chirpboard.app.download.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    ContinuousAudioTranscriberPreference,
    PcmFloatFileTranscriberProvider {
    private val switchMutex = Mutex()
    private val sherpa = SherpaRecognizerProvider(context.applicationContext, downloader)
    private val gguf = GgufRecognizerProvider(downloader, selectionStore)

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
            if (!initializeModel(modelId)) {
                return@withLock LocalSpeechModelActivationResult.Failed("Could not load the selected speech model")
            }

            try {
                selectionStore.selectModel(modelId)
            } catch (error: Exception) {
                val rolledBack =
                    if (provider(current) === target) {
                        initializeModel(current)
                    } else {
                        target.release()
                        initializeModel(current)
                    }
                if (!rolledBack) {
                    target.release()
                    Log.e(TAG, "Model selection persistence failed and the prior model could not be restored")
                }
                return@withLock LocalSpeechModelActivationResult.Failed(
                    error.message ?: "Could not save the selected speech model",
                )
            }

            Log.i(TAG, "Activated local speech model ${modelId.persistedValue}")
            if (current != modelId && provider(current) !== target) provider(current).release()
            LocalSpeechModelActivationResult.Activated
        }

    override suspend fun isComputeBackendAvailable(backend: LocalSpeechComputeBackend): Boolean =
        when (backend) {
            LocalSpeechComputeBackend.CPU -> true
            // First read dlopens the native libraries; keep that off the caller's thread.
            LocalSpeechComputeBackend.VULKAN ->
                withContext(Dispatchers.Default) { GgufNativeCapabilities.supportsVulkan }
        }

    override suspend fun activateComputeBackend(
        backend: LocalSpeechComputeBackend,
    ): LocalSpeechComputeBackendActivationResult =
        switchMutex.withLock {
            val modelId = selectionStore.selectedModel.value
            if (!isGgufModel(modelId)) {
                return@withLock LocalSpeechComputeBackendActivationResult.Failed(
                    "CPU and Vulkan selection applies only to the 110M GGUF models",
                )
            }
            if (backend == LocalSpeechComputeBackend.VULKAN && !GgufNativeCapabilities.supportsVulkan) {
                return@withLock LocalSpeechComputeBackendActivationResult.Failed(
                    "This build does not include the Vulkan native backend",
                )
            }
            val priorBackend = selectionStore.selectedComputeBackend.value
            val config = GgufRuntimeConfig(modelId, backend)
            if (!gguf.initialize(config)) {
                return@withLock LocalSpeechComputeBackendActivationResult.Failed(
                    "Could not load the selected compute backend",
                )
            }
            try {
                selectionStore.selectComputeBackend(backend)
            } catch (error: Exception) {
                if (!gguf.initialize(GgufRuntimeConfig(modelId, effectiveGgufComputeBackend(priorBackend)))) {
                    gguf.release()
                    Log.e(TAG, "Compute selection persistence failed and the prior backend could not be restored")
                }
                return@withLock LocalSpeechComputeBackendActivationResult.Failed(
                    error.message ?: "Could not save the selected compute backend",
                )
            }
            val actual = GgufRecognizerManager.actualComputeBackend(config) ?: LocalSpeechComputeBackend.CPU
            LocalSpeechComputeBackendActivationResult.Activated(
                actualBackend = actual,
                usedCpuFallback = backend == LocalSpeechComputeBackend.VULKAN && actual == LocalSpeechComputeBackend.CPU,
            )
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

                LocalSpeechModelId.PARAKEET_TDT_110M_Q6_K,
                LocalSpeechModelId.PARAKEET_TDT_110M_Q4_K_M,
                -> !GgufRecognizerManager.isResident() || GgufRecognizerManager.releaseIfUnused()
            }
        }

    private suspend fun initializeModel(modelId: LocalSpeechModelId): Boolean =
        if (isGgufModel(modelId)) {
            gguf.initialize(
                GgufRuntimeConfig(
                    modelId,
                    effectiveGgufComputeBackend(selectionStore.selectedComputeBackend.value),
                ),
            )
        } else {
            sherpa.initialize()
        }

    private fun isGgufModel(modelId: LocalSpeechModelId): Boolean =
        selectionStore.modelInfo(modelId).backend == dev.chirpboard.app.core.transcription.LocalSpeechBackend.TRANSCRIBE_GGUF

    private fun provider(modelId: LocalSpeechModelId): TranscriberProvider =
        when (modelId) {
            LocalSpeechModelId.PARAKEET_TDT_600M -> sherpa
            LocalSpeechModelId.PARAKEET_CTC_110M_Q8,
            LocalSpeechModelId.PARAKEET_TDT_110M_Q6_K,
            LocalSpeechModelId.PARAKEET_TDT_110M_Q4_K_M,
            -> gguf
        }

    private companion object {
        const val TAG = "SelectableTranscriber"
    }
}
