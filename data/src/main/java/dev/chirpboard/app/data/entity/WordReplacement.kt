package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a word replacement rule for transcription.
 */
@Entity(
    tableName = "word_replacements",
    indices = [Index("original"), Index("enabled", "original")],
)
@Keep
data class WordReplacement(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),
    /** Original word/phrase to match */
    val original: String,
    /** Replacement word/phrase */
    val replacement: String,
    /** Whether matching is case-sensitive */
    val caseSensitive: Boolean = false,
    /** Whether this replacement is enabled */
    val enabled: Boolean = true,
)
