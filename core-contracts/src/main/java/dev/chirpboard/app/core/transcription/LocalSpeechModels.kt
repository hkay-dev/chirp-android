package dev.chirpboard.app.core.transcription

import kotlinx.coroutines.flow.StateFlow

enum class LocalSpeechModelId(val persistedValue: String) {
    PARAKEET_TDT_600M("parakeet_tdt_600m"),
    PARAKEET_CTC_110M_Q8("parakeet_ctc_110m_q8"),
    ;

    companion object {
        fun fromPersistedValue(value: String?): LocalSpeechModelId =
            entries.firstOrNull { it.persistedValue == value } ?: PARAKEET_TDT_600M
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
                displayName = "Parakeet CTC 110M Q8",
                shortDescription = "Fast, compact, English-only offline transcription",
                backend = LocalSpeechBackend.TRANSCRIBE_GGUF,
                approximateSizeMb = 135,
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

    fun modelInfo(modelId: LocalSpeechModelId): LocalSpeechModelInfo

    suspend fun selectModel(modelId: LocalSpeechModelId)
}

sealed interface LocalSpeechModelActivationResult {
    data object Activated : LocalSpeechModelActivationResult
    data object ModelNotDownloaded : LocalSpeechModelActivationResult
    data class Failed(val message: String) : LocalSpeechModelActivationResult
}

interface LocalSpeechModelActivator {
    suspend fun activate(modelId: LocalSpeechModelId): LocalSpeechModelActivationResult
}

/** Optional guard that keeps model files intact during an active native decode. */
interface LocalSpeechModelDeletionGuard {
    suspend fun releaseForDeletion(modelId: LocalSpeechModelId): Boolean
}
