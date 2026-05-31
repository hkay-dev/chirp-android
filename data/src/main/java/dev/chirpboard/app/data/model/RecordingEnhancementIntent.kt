package dev.chirpboard.app.data.model

import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript

data class RecordingEnhancementIntent(
    val processingModeId: String?,
    val autoTitle: Boolean,
    val autoSummary: Boolean,
) {
    val hasRequestedWork: Boolean
        get() = processingModeId != null || autoTitle || autoSummary
}

data class RecordingEnhancementSnapshot(
    val recording: Recording,
    val transcript: Transcript,
    val intent: RecordingEnhancementIntent,
)

data class RecordingEnhancementResult(
    val processedText: String?,
    val processingMode: String?,
    val title: String?,
    val summary: String?,
)
