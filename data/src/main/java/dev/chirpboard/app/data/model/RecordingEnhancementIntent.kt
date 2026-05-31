package dev.chirpboard.app.data.model

import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import java.util.Date
import java.util.UUID

enum class EnhancementSubworkStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
}

data class RecordingEnhancementSubworkState(
    val requested: Boolean,
    val status: EnhancementSubworkStatus,
    val errorMessage: String? = null,
) {
    val unresolved: Boolean
        get() = requested && status in setOf(EnhancementSubworkStatus.PENDING, EnhancementSubworkStatus.FAILED)
}

data class RecordingEnhancementIntent(
    val processingModeId: String?,
    val processingModeLabel: String? = null,
    val processingModeType: String? = null,
    val processingModePrompt: String? = null,
    val autoTitle: Boolean,
    val autoSummary: Boolean,
    val llmProviderId: String? = null,
    val llmModelId: String? = null,
    val legacyRequiresResolution: Boolean = false,
) {
    val hasRequestedWork: Boolean
        get() = processingModeId != null || autoTitle || autoSummary
}

data class RecordingEnhancementSnapshot(
    val recording: Recording,
    val transcript: Transcript,
    val execution: RecordingEnhancementExecutionSnapshot,
) {
    val intent: RecordingEnhancementIntent
        get() =
            RecordingEnhancementIntent(
                processingModeId = execution.processingModeId,
                processingModeLabel = execution.processingModeLabel,
                processingModeType = execution.processingModeType,
                processingModePrompt = execution.processingModePrompt,
                autoTitle = execution.title.requested,
                autoSummary = execution.summary.requested,
                llmProviderId = execution.llmProviderId,
                llmModelId = execution.llmModelId,
                legacyRequiresResolution = execution.legacyRequiresResolution,
            )
}

data class RecordingEnhancementExecutionSnapshot(
    val recordingId: UUID,
    val schemaVersion: Int = 1,
    val sourceTranscriptRevision: String,
    val sourceProcessedTextRevision: String?,
    val processingModeId: String?,
    val processingModeLabel: String?,
    val processingModeType: String?,
    val processingModePrompt: String?,
    val processingMode: RecordingEnhancementSubworkState,
    val title: RecordingEnhancementSubworkState,
    val summary: RecordingEnhancementSubworkState,
    val llmProviderId: String?,
    val llmModelId: String?,
    val activeEnhancementExecutionToken: String?,
    val legacyRequiresResolution: Boolean,
    val createdAt: Date,
    val lastAttemptedAt: Date?,
    val lastErrorMessage: String?,
) {
    val hasRequestedWork: Boolean
        get() = processingMode.requested || title.requested || summary.requested || legacyRequiresResolution

    val hasUnresolvedWork: Boolean
        get() = processingMode.unresolved || title.unresolved || summary.unresolved || legacyRequiresResolution
}

data class RecordingEnhancementResult(
    val processedText: String?,
    val processingMode: String?,
    val title: String?,
    val summary: String?,
    val processingModeStatus: EnhancementSubworkStatus? = null,
    val processingModeError: String? = null,
    val titleStatus: EnhancementSubworkStatus? = null,
    val titleError: String? = null,
    val summaryStatus: EnhancementSubworkStatus? = null,
    val summaryError: String? = null,
)
