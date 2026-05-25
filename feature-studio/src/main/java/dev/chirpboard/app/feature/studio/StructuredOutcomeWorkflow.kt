package dev.chirpboard.app.feature.studio

import androidx.compose.runtime.Stable
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.StructuredOutcomeGenerationStatus
import dev.chirpboard.app.data.model.StructuredOutcomeSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.security.MessageDigest

@Stable
data class StructuredOutcomeSectionState(
    val isVisible: Boolean = false,
    val isGenerating: Boolean = false,
    val hasTranscriptText: Boolean = false,
    val hasReadySnapshot: Boolean = false,
    val hasReadyItems: Boolean = false,
    val isStale: Boolean = false,
    val failureMessage: String? = null,
    val generatedAtMs: Long? = null,
    val tasks: ImmutableList<StructuredOutcomeItemUi> = persistentListOf(),
    val decisions: ImmutableList<StructuredOutcomeItemUi> = persistentListOf(),
    val followUps: ImmutableList<StructuredOutcomeItemUi> = persistentListOf(),
) {
    val canRunGeneration: Boolean
        get() = isVisible && hasTranscriptText && !isGenerating

    val hasAnyGroups: Boolean
        get() = tasks.isNotEmpty() || decisions.isNotEmpty() || followUps.isNotEmpty()
}

@Stable
data class StructuredOutcomeItemUi(
    val id: String,
    val group: StructuredOutcomeGroup,
    val text: String,
)

enum class StructuredOutcomeGroup {
    TASKS,
    DECISIONS,
    FOLLOW_UPS,
}

internal fun buildStructuredOutcomeSectionState(
    recordingStatus: RecordingStatus?,
    effectiveTranscriptText: String,
    snapshot: StructuredOutcomeSnapshot?,
    isGenerating: Boolean,
    currentRevision: String = effectiveTranscriptText.structuredOutcomeRevision(),
): StructuredOutcomeSectionState {
    val isVisible = recordingStatus == RecordingStatus.COMPLETED
    val hasTranscriptText = effectiveTranscriptText.isNotBlank()
    val hasReadySnapshot = snapshot?.hasReadyPayload == true
    val tasks = snapshot.orEmptyGroup(StructuredOutcomeGroup.TASKS)
    val decisions = snapshot.orEmptyGroup(StructuredOutcomeGroup.DECISIONS)
    val followUps = snapshot.orEmptyGroup(StructuredOutcomeGroup.FOLLOW_UPS)

    return StructuredOutcomeSectionState(
        isVisible = isVisible,
        isGenerating = isGenerating,
        hasTranscriptText = hasTranscriptText,
        hasReadySnapshot = hasReadySnapshot,
        hasReadyItems = tasks.isNotEmpty() || decisions.isNotEmpty() || followUps.isNotEmpty(),
        isStale = hasReadySnapshot && snapshot?.sourceTranscriptRevision != currentRevision,
        failureMessage = snapshot?.takeIf { it.generationStatus == StructuredOutcomeGenerationStatus.FAILED }?.failureMessage,
        generatedAtMs = snapshot?.generatedAt?.time,
        tasks = tasks,
        decisions = decisions,
        followUps = followUps,
    )
}

internal fun validateStructuredOutcomeGenerationRequest(
    recordingStatus: RecordingStatus?,
    effectiveTranscriptText: String,
    hasApiKey: Boolean,
    isGenerating: Boolean,
): String? =
    when {
        recordingStatus != RecordingStatus.COMPLETED -> "Structured outcomes are available after processing finishes"
        effectiveTranscriptText.isBlank() -> "Structured outcomes need transcript text first"
        !hasApiKey -> "Add a Gemini API key in Settings to generate structured outcomes"
        isGenerating -> "Structured outcomes are already generating"
        else -> null
    }

internal fun buildStructuredOutcomeAskAiDraft(item: StructuredOutcomeItemUi): String =
    when (item.group) {
        StructuredOutcomeGroup.TASKS -> {
            "Help me act on this task from the recording:\n\n${item.text}"
        }

        StructuredOutcomeGroup.DECISIONS -> {
            "Help me analyze this decision from the recording:\n\n${item.text}"
        }

        StructuredOutcomeGroup.FOLLOW_UPS -> {
            "Help me draft or plan this follow-up from the recording:\n\n${item.text}"
        }
    }

internal fun String.structuredOutcomeRevision(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(trim().toByteArray()).joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}

private fun StructuredOutcomeSnapshot?.orEmptyGroup(group: StructuredOutcomeGroup): ImmutableList<StructuredOutcomeItemUi> {
    val items =
        when (group) {
            StructuredOutcomeGroup.TASKS -> this?.tasks.orEmpty()
            StructuredOutcomeGroup.DECISIONS -> this?.decisions.orEmpty()
            StructuredOutcomeGroup.FOLLOW_UPS -> this?.followUps.orEmpty()
        }

    return items
        .mapIndexed { index, text ->
            StructuredOutcomeItemUi(
                id = "${group.name.lowercase()}-$index",
                group = group,
                text = text,
            )
        }.toImmutableList()
}
