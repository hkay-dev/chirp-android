package dev.chirpboard.app.core.llm

import kotlinx.coroutines.flow.Flow

data class ProcessingMode(
    val id: String,
    val displayName: String,
) {
    companion object {
        val Proofread = ProcessingMode(ProcessingModeDefaults.DEFAULT_MODE_ID, "Proofread")
    }
}

data class ProcessingModeListItem(
    val id: String,
    val name: String,
)

data class LlmRuntimeSnapshot(
    val providerId: String?,
    val modelId: String?,
)

data class ResolvedProcessingModeSnapshot(
    val id: String,
    val label: String?,
    val type: String?,
    val prompt: String?,
)

data class RecordingTextEnhancementContext(
    val text: String,
    val providerId: String? = null,
    val modelId: String? = null,
)

object ProcessingModeDefaults {
    const val DEFAULT_MODE_ID = "proofread"

    val builtInSelectableIds: List<String> =
        listOf("proofread", "formal", "casual", "email", "code", "smart")

    fun displayName(modeId: String): String =
        when (modeId) {
            "proofread" -> "Proofread"
            "formal" -> "Formal"
            "casual" -> "Casual"
            "email" -> "Email"
            "code" -> "Code"
            "smart" -> "Smart"
            else -> modeId
        }
}

interface ProcessingModePort {
    val currentMode: Flow<ProcessingMode>

    val selectableModes: Flow<List<ProcessingModeListItem>>

    suspend fun setModeById(modeId: String)
}

interface RecordingTextEnhancementPort : RecordingTextEnrichment {
    suspend fun isEnhancementAvailable(): Boolean

    suspend fun isEnhancementAvailable(providerId: String?): Boolean = isEnhancementAvailable()

    suspend fun defaultAutoTitleEnabled(): Boolean

    suspend fun defaultAutoSummaryEnabled(): Boolean

    suspend fun runtimeSnapshot(): LlmRuntimeSnapshot =
        LlmRuntimeSnapshot(providerId = null, modelId = null)

    suspend fun resolveProcessingModeSnapshot(
        text: String,
        processingModeId: String,
    ): ResolvedProcessingModeSnapshot =
        ResolvedProcessingModeSnapshot(
            id = processingModeId,
            label = processingModeId,
            type = null,
            prompt = null,
        )

    fun createContext(
        text: String,
        providerId: String? = null,
        modelId: String? = null,
    ): RecordingTextEnhancementContext =
        RecordingTextEnhancementContext(
            text = text,
            providerId = providerId,
            modelId = modelId,
        )

    suspend fun process(
        text: String,
        processingModeId: String,
    ): Result<String>

    suspend fun processResolved(
        text: String,
        prompt: String?,
        providerId: String?,
        modelId: String?,
        fallbackProcessingModeId: String,
    ): Result<String> = process(text, fallbackProcessingModeId)

    suspend fun processResolved(
        context: RecordingTextEnhancementContext,
        prompt: String?,
        fallbackProcessingModeId: String,
    ): Result<String> =
        processResolved(
            text = context.text,
            prompt = prompt,
            providerId = context.providerId,
            modelId = context.modelId,
            fallbackProcessingModeId = fallbackProcessingModeId,
        )

    suspend fun generateTitle(
        transcript: String,
        providerId: String?,
        modelId: String?,
    ): Result<String> = generateTitle(transcript)

    suspend fun generateTitle(context: RecordingTextEnhancementContext): Result<String> =
        generateTitle(context.text, context.providerId, context.modelId)

    suspend fun generateSummary(
        transcript: String,
        providerId: String?,
        modelId: String?,
    ): Result<String> = generateSummary(transcript)

    suspend fun generateSummary(context: RecordingTextEnhancementContext): Result<String> =
        generateSummary(context.text, context.providerId, context.modelId)
}
