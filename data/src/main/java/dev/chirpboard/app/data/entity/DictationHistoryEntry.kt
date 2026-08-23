package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * HIST-1: a text-only record of a successfully delivered quick dictation (keyboard IME or
 * the RECOGNIZE_SPEECH voice dialog). Exists because delivery to the caller app is not
 * durable: some editors accept the commit and then drop it (dead InputConnection, app
 * restart), and the quick-input notification times out. This table is the recovery path.
 *
 * Deliberately audio-free and capped ([DICTATION_HISTORY_MAX_ENTRIES], pruned on insert):
 * it is a clipboard-history-style convenience, not the recordings library. Incognito
 * (IME-3) and secure (IME-6) sessions never reach the persistence hook that writes it.
 */
@Entity(
    tableName = "dictation_history",
    indices = [Index("createdAt")],
)
@Keep
data class DictationHistoryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Post-word-replacement raw transcript; never blank. */
    val rawText: String,
    /** LLM-polished text when polish succeeded, null otherwise. */
    val processedText: String?,
    val createdAt: Date = Date(),
)

/** Retention cap for [DictationHistoryEntry] rows; oldest rows are pruned on insert. */
const val DICTATION_HISTORY_MAX_ENTRIES = 50
