package dev.chirpboard.app.core.export

import java.util.UUID

data class TranscriptExportRecording(
    val title: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val sourceName: String,
    /** Recording row id for export bookkeeping; null for unsaved captures. */
    val id: UUID? = null,
)

/**
 * Outcome of an [TranscriptExportPort.exportIfEnabled] call that did not fail.
 * [exportedUri] is null when export was skipped (disabled or no vault configured).
 */
data class TranscriptExportOutcome(
    val exportedUri: String?,
) {
    val exported: Boolean
        get() = exportedUri != null
}

interface TranscriptExportPort {
    /**
     * Exports the transcript when auto-export applies. Export runs when the global
     * auto-export toggle is on OR [requestedByProfile] is true (the recording's
     * profile opted in); the destination is always the globally configured vault.
     */
    suspend fun exportIfEnabled(
        recording: TranscriptExportRecording,
        transcript: String,
        summary: String? = null,
        tags: List<String> = emptyList(),
        requestedByProfile: Boolean = false,
    ): Result<TranscriptExportOutcome>
}
