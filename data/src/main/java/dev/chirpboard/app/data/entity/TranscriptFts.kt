package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Fts4

/**
 * Full-text index over the searchable transcript columns (SRCH-1).
 *
 * External-content FTS4: the rows live in [Transcript] and Room keeps the index in sync with
 * generated triggers keyed on the transcripts rowid, so nothing writes to this table directly.
 * Only the three text columns the library search actually matches are indexed; summary and the
 * correction source text are derived/duplicate content and would only inflate the index.
 */
@Entity(tableName = "transcripts_fts")
@Fts4(contentEntity = Transcript::class)
@Keep
data class TranscriptFts(
    val rawText: String,
    val processedText: String? = null,
    val manualCorrectionText: String? = null,
)
