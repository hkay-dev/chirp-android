package dev.chirpboard.app.core.export

data class TranscriptExportRecording(
    val title: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val sourceName: String,
)

interface TranscriptExportPort {
    suspend fun exportIfEnabled(
        recording: TranscriptExportRecording,
        transcript: String,
        summary: String? = null,
    ): Result<Unit>
}
