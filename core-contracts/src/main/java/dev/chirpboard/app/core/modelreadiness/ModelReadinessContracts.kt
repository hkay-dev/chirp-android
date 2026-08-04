package dev.chirpboard.app.core.modelreadiness

import kotlinx.coroutines.flow.StateFlow
import dev.chirpboard.app.core.transcription.LocalSpeechModelId

enum class ModelReadinessVerificationSource {
    PROCESS_CACHE,
    PERSISTED_CACHE,
    CHECKSUM_VERIFICATION,
}

enum class ModelReadinessUnavailableReason {
    MISSING_MODEL_FILES,
    INTEGRITY_MISMATCH,
    STORAGE_ACCESS_DENIED,
}

enum class VerificationTrigger {
    APP_STARTUP,
    QUEUED_TRANSCRIPTION,
    RECOVERY,
    HOME_VISIBLE,
    HOME_RECORD_TAP,
    KEYBOARD_DICTATION,
    MODEL_DOWNLOAD,
}

sealed interface ModelReadinessState {
    data object Unknown : ModelReadinessState

    data class Checking(
        val trigger: VerificationTrigger,
        val startedAtEpochMs: Long,
    ) : ModelReadinessState

    data class Ready(
        val verifiedAtEpochMs: Long,
        val source: ModelReadinessVerificationSource,
    ) : ModelReadinessState

    data class Unavailable(
        val reason: ModelReadinessUnavailableReason,
    ) : ModelReadinessState

    data class Error(
        val message: String,
    ) : ModelReadinessState
}

sealed interface ModelReadyResult {
    data class Ready(
        val source: ModelReadinessVerificationSource,
    ) : ModelReadyResult

    data class Unavailable(
        val reason: ModelReadinessUnavailableReason,
    ) : ModelReadyResult

    data class Error(
        val message: String,
    ) : ModelReadyResult
}

data class ModelReadinessEvaluation(
    val isReady: Boolean,
    val verificationSource: ModelReadinessVerificationSource? = null,
    val unavailableReason: ModelReadinessUnavailableReason? = null,
)

interface SpeechModelStore {
    suspend fun evaluateReadiness(): ModelReadinessEvaluation

    suspend fun evaluateReadiness(modelId: LocalSpeechModelId): ModelReadinessEvaluation = evaluateReadiness()

    suspend fun deleteModel(): Boolean

    suspend fun deleteModel(modelId: LocalSpeechModelId): Boolean = deleteModel()

    suspend fun getDownloadedSize(): Long

    suspend fun getDownloadedSize(modelId: LocalSpeechModelId): Long = getDownloadedSize()

    fun invalidateVerificationCache()

    companion object {
        const val DISPLAY_NAME = "Parakeet TDT 0.6B"
        const val APPROXIMATE_SIZE_MB = 659
    }
}

interface SpeechModelReadinessGate {
    val state: StateFlow<ModelReadinessState>

    fun warmupIfNeeded(trigger: VerificationTrigger = VerificationTrigger.APP_STARTUP)

    fun invalidate()

    suspend fun ensureReady(trigger: VerificationTrigger): ModelReadyResult
}

/**
 * Process-wide hint for keeping the local recognizer ready around real IME use. Implementations
 * may load or retain model resources, but must never open the microphone from this callback.
 */
interface LocalRecognizerWarmWindow {
    fun onImeVisibilityChanged(visible: Boolean)
}
