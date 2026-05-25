package dev.chirpboard.app.feature.studio

import androidx.compose.runtime.Stable
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.llm.client.TranscriptPassageAction
import dev.chirpboard.app.feature.llm.model.ChatMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class ProcessingStudioState(
    val isLoading: Boolean = false,
    val status: RecordingStatus? = null,
    val errorMessage: String? = null,
    val transcript: ProcessingStudioTranscript = ProcessingStudioTranscript.Empty,
    val renderedTranscriptText: String = "",
    val effectiveTranscriptText: String = "",
    val transcriptDraft: String = "",
    val isEditingTranscript: Boolean = false,
    val isSelectingTranscript: Boolean = false,
    val selectedTranscriptPassage: String = "",
    val transcriptSelectionActionInFlight: TranscriptPassageAction? = null,
    val transcriptSelectionResult: TranscriptSelectionResult? = null,
    val hasManualCorrection: Boolean = false,
    val canPromoteManualCorrection: Boolean = false,
    val activeTranscriptSegmentIndex: Int = -1,
    val summary: String = "",
    val structuredOutcomeSection: StructuredOutcomeSectionState = StructuredOutcomeSectionState(),
    val chatMessages: ImmutableList<ChatMessage> = persistentListOf(),
    val chatDraft: String = "",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isTyping: Boolean = false,
    val title: String = "",
    val createdAt: Long = 0L,
    val isEditingTitle: Boolean = false,
    val editedTitle: String = "",
    val audioPath: String = "",
    val source: RecordingSource? = null,
    val recoveryDiagnostics: RecoveryDiagnosticsUi = RecoveryDiagnosticsUi(),
    val recoveryActions: TranscriptionRecoveryActionsUi = TranscriptionRecoveryActionsUi(
        showPendingRecovery = false,
        showEnhancementRecovery = false,
        showRetranscribeFromEnhancing = false,
        showFailedRetry = false,
        actionsEnabled = true,
    ),
)
