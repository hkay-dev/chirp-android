package dev.parakeeboard.app.data.entity

import androidx.room.*
import java.util.UUID

/**
 * Represents a recording profile with settings.
 */
@Entity(tableName = "profiles")
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
    val sortOrder: Int = 0
) {
    /** Parse defaultTagIds into list of UUIDs */
    fun getDefaultTags(): List<UUID> {
        return defaultTagIds?.split(",")
            ?.filter { it.isNotBlank() }
            ?.map { UUID.fromString(it.trim()) }
            ?: emptyList()
    }
    
    /** Create defaultTagIds from list of UUIDs */
    fun withDefaultTags(tags: List<UUID>): Profile {
        return copy(defaultTagIds = tags.joinToString(",") { it.toString() })
    }
}
