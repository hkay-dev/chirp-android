package dev.chirpboard.app.feature.studio

import androidx.annotation.StringRes
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

// I18N: validators return string resource ids; the ViewModel resolves them with its context.
@StringRes
internal fun validateStructuredOutcomeGenerationRequest(
    recordingStatus: RecordingStatus?,
    effectiveTranscriptText: String,
    hasApiKey: Boolean,
    isGenerating: Boolean,
): Int? =
    when {
        recordingStatus != RecordingStatus.COMPLETED -> R.string.rec_structured_unavailable
        effectiveTranscriptText.isBlank() -> R.string.rec_structured_no_transcript
        !hasApiKey -> R.string.rec_msg_structured_api_key_missing
        isGenerating -> R.string.rec_msg_structured_already_generating
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
    val bytes = digest.digest(trim().toByteArray())
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

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
