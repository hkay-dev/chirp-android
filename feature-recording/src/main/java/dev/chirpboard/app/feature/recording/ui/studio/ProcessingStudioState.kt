package dev.chirpboard.app.feature.recording.ui.studio

import androidx.compose.runtime.Stable
import dev.chirpboard.app.feature.llm.model.ChatMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class TranscriptWord(
    val word: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val confidence: Float
)

@Stable
data class ProcessingStudioState(
    val isLoading: Boolean = false,
    val transcriptWords: ImmutableList<TranscriptWord> = persistentListOf(),
    val summary: String = "",
    val chatMessages: ImmutableList<ChatMessage> = persistentListOf(),
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isTyping: Boolean = false,
    val title: String = "",
    val createdAt: Long = 0L,
    val isEditingTitle: Boolean = false,
    val editedTitle: String = "",
    val audioPath: String = ""
)
