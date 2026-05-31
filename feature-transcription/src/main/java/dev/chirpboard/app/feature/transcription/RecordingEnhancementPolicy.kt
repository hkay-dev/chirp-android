package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.data.entity.Profile

internal data class RecordingEnhancementPolicy(
    val processingModeId: String?,
    val autoTitle: Boolean,
    val autoSummary: Boolean,
) {
    val hasRequestedWork: Boolean
        get() = processingModeId != null || autoTitle || autoSummary
}

internal fun resolveRecordingEnhancementPolicy(
    profile: Profile?,
    globalAutoTitle: Boolean,
    globalAutoSummary: Boolean,
): RecordingEnhancementPolicy =
    if (profile != null) {
        RecordingEnhancementPolicy(
            processingModeId = profile.defaultProcessingMode?.takeIf { it.isNotBlank() },
            autoTitle = profile.autoTitle,
            autoSummary = profile.autoSummary,
        )
    } else {
        RecordingEnhancementPolicy(
            processingModeId = null,
            autoTitle = globalAutoTitle,
            autoSummary = globalAutoSummary,
        )
    }
