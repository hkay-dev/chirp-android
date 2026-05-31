package dev.chirpboard.app.data.model

import java.util.UUID

data class TranscriptPreview(
    val recordingId: UUID,
    val summary: String?,
    val previewText: String?,
)
