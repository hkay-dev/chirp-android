package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a recording profile with settings.
 */
@Entity(
    tableName = "profiles",
    indices = [Index("sortOrder", "name")]
)
@Keep
data class Profile(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),
    /** Display name */
    val name: String,
    /** Emoji icon for quick recognition */
    val icon: String? = null,
    /** Default LLM processing mode (null = no processing) */
    val defaultProcessingMode: String? = null,
    /** Start transcription immediately after recording */
    val autoTranscribe: Boolean = true,
    /** Generate title via LLM (APP/WIDGET only) */
    val autoTitle: Boolean = false,
    /** Generate list subline summary (APP/WIDGET only) */
    val autoSummary: Boolean = false,
    /** Obsidian vault path override (null = use global or no export) */
    val obsidianVaultPath: String? = null,
    /** Auto-export to Obsidian after processing */
    val autoExportToObsidian: Boolean = false,
    /** Default tags to apply (stored as comma-separated UUIDs) */
    val defaultTagIds: String? = null,
    /** Position for ordering in UI */
    val sortOrder: Int = 0,
    /** Keep this profile visible in home quick starts even when not recently used */
    val isQuickStartPinned: Boolean = false,
 ) {
    /** Parse defaultTagIds into list of UUIDs */
    fun getDefaultTags(): List<UUID> =
        defaultTagIds
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.map { UUID.fromString(it.trim()) }
            ?: emptyList()

    /** Create defaultTagIds from list of UUIDs */
    fun withDefaultTags(tags: List<UUID>): Profile = copy(defaultTagIds = tags.joinToString(",") { it.toString() })
}
