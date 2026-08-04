package dev.chirpboard.app.core.transcription

import kotlinx.coroutines.flow.StateFlow

enum class LocalSpeechModelId(val persistedValue: String) {
    PARAKEET_TDT_600M("parakeet_tdt_600m"),
    // Keep this persisted value for alpha installs that already selected the 110M Q8 model.
    // The converted artifact uses the TDT head; the source checkpoint's CTC head is not present.
    PARAKEET_CTC_110M_Q8("parakeet_ctc_110m_q8"),
    PARAKEET_TDT_110M_Q6_K("parakeet_tdt_110m_q6_k"),
    PARAKEET_TDT_110M_Q4_K_M("parakeet_tdt_110m_q4_k_m"),
    ;

    companion object {
        fun fromPersistedValue(value: String?): LocalSpeechModelId =
            entries.firstOrNull { it.persistedValue == value } ?: PARAKEET_CTC_110M_Q8
    }
}

enum class LocalSpeechComputeBackend(val persistedValue: String) {
    CPU("cpu"),
    VULKAN("vulkan"),
    ;

    companion object {
        fun fromPersistedValue(value: String?): LocalSpeechComputeBackend =
            entries.firstOrNull { it.persistedValue == value } ?: CPU
    }
}

enum class LocalSpeechBackend {
    SHERPA_ONNX,
    TRANSCRIBE_GGUF,
}

data class LocalSpeechModelInfo(
    val id: LocalSpeechModelId,
    val displayName: String,
    val shortDescription: String,
    val backend: LocalSpeechBackend,
    val approximateSizeMb: Int,
    val englishOnly: Boolean,
    val supportsStreamingPreview: Boolean,
    val supportsWordTimings: Boolean,
)

object LocalSpeechModelCatalog {
    val models: List<LocalSpeechModelInfo> =
        listOf(
            LocalSpeechModelInfo(
                id = LocalSpeechModelId.PARAKEET_CTC_110M_Q8,
                displayName = "Parakeet 110M Q8",
                shortDescription = "Reliable default with near-reference accuracy",
                backend = LocalSpeechBackend.TRANSCRIBE_GGUF,
                approximateSizeMb = 135,
                englishOnly = true,
                supportsStreamingPreview = false,
                supportsWordTimings = false,
            ),
            LocalSpeechModelInfo(
                id = LocalSpeechModelId.PARAKEET_TDT_110M_Q6_K,
                displayName = "Parakeet 110M Q6_K",
                shortDescription = "Experimental smaller quant with 2.44% test-clean WER",
                backend = LocalSpeechBackend.TRANSCRIBE_GGUF,
                approximateSizeMb = 112,
                englishOnly = true,
                supportsStreamingPreview = false,
                supportsWordTimings = false,
            ),
            LocalSpeechModelInfo(
                id = LocalSpeechModelId.PARAKEET_TDT_110M_Q4_K_M,
                displayName = "Parakeet 110M Q4_K_M",
                shortDescription = "Experimental smallest quant with 2.53% test-clean WER",
                backend = LocalSpeechBackend.TRANSCRIBE_GGUF,
                approximateSizeMb = 90,
                englishOnly = true,
                supportsStreamingPreview = false,
                supportsWordTimings = false,
            ),
            LocalSpeechModelInfo(
                id = LocalSpeechModelId.PARAKEET_TDT_600M,
                displayName = "Parakeet TDT 0.6B",
                shortDescription = "Larger offline model with streaming preview",
                backend = LocalSpeechBackend.SHERPA_ONNX,
                approximateSizeMb = 659,
                englishOnly = true,
                supportsStreamingPreview = true,
                supportsWordTimings = false,
            ),
        )

    fun requireModel(modelId: LocalSpeechModelId): LocalSpeechModelInfo =
        models.first { it.id == modelId }
}

interface LocalSpeechModelSelectionStore {
    val availableModels: List<LocalSpeechModelInfo>
    val selectedModel: StateFlow<LocalSpeechModelId>
    val selectedComputeBackend: StateFlow<LocalSpeechComputeBackend>

    fun modelInfo(modelId: LocalSpeechModelId): LocalSpeechModelInfo

    suspend fun selectModel(modelId: LocalSpeechModelId)

    suspend fun selectComputeBackend(backend: LocalSpeechComputeBackend)
}

sealed interface LocalSpeechModelActivationResult {
    data object Activated : LocalSpeechModelActivationResult
    data object ModelNotDownloaded : LocalSpeechModelActivationResult
    data class Failed(val message: String) : LocalSpeechModelActivationResult
}

interface LocalSpeechModelActivator {
    suspend fun activate(modelId: LocalSpeechModelId): LocalSpeechModelActivationResult
}

sealed interface LocalSpeechComputeBackendActivationResult {
    data class Activated(
        val actualBackend: LocalSpeechComputeBackend,
        val usedCpuFallback: Boolean,
    ) : LocalSpeechComputeBackendActivationResult

    data class Failed(val message: String) : LocalSpeechComputeBackendActivationResult
}

interface LocalSpeechComputeBackendActivator {
    suspend fun activateComputeBackend(
        backend: LocalSpeechComputeBackend,
    ): LocalSpeechComputeBackendActivationResult
}

/** Optional guard that keeps model files intact during an active native decode. */
interface LocalSpeechModelDeletionGuard {
    suspend fun releaseForDeletion(modelId: LocalSpeechModelId): Boolean
}
