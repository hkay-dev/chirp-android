package dev.chirpboard.app.data.model

/**
 * Full-table recording aggregates for the home header (DAT-006).
 *
 * Computed by a dedicated `COUNT(*)/SUM(...)` query so the header stats stay truthful even when
 * the home list itself is capped to the latest rows.
 */
data class RecordingLibraryStats(
    val totalCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val completedCount: Int = 0,
)
