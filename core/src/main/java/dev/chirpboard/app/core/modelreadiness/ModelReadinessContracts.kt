package dev.chirpboard.app.core.modelreadiness

import kotlinx.coroutines.flow.StateFlow

enum class ModelReadinessVerificationSource {
    PROCESS_CACHE,
    PERSISTED_CACHE,
    CHECKSUM_VERIFICATION,
}

enum class ModelReadinessUnavailableReason {
    MISSING_MODEL_FILES,
    INTEGRITY_MISMATCH,
}

enum class VerificationTrigger {
    APP_STARTUP,
    HOME_VISIBLE,
    HOME_RECORD_TAP,
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

fun interface ModelReadinessVerifier {
    suspend fun verify(): ModelReadinessEvaluation
}

sealed interface SpeechModelDownloadState {
    data class Progress(
        val file: String,
        val progress: Float,
    ) : SpeechModelDownloadState

    data object Complete : SpeechModelDownloadState

    data class Error(
        val message: String,
    ) : SpeechModelDownloadState
}

interface SpeechModelStore {
    suspend fun evaluateReadiness(): ModelReadinessEvaluation

    fun downloadModel(): kotlinx.coroutines.flow.Flow<SpeechModelDownloadState>

    suspend fun deleteModel(): Boolean

    suspend fun getDownloadedSize(): Long

    fun invalidateVerificationCache()

    companion object {
        const val DISPLAY_NAME = "Parakeet TDT 0.6B"
        const val APPROXIMATE_SIZE_MB = 659
    }
}

interface SpeechModelReadinessGate {
    val state: StateFlow<ModelReadinessState>

    fun warmupIfNeeded(trigger: VerificationTrigger = VerificationTrigger.APP_STARTUP)

    suspend fun ensureReady(trigger: VerificationTrigger): ModelReadyResult
}
