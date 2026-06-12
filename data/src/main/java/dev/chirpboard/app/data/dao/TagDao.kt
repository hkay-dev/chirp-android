package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.chirpboard.app.data.entity.ProfileDefaultTag
import dev.chirpboard.app.data.entity.RecordingTag
import dev.chirpboard.app.data.entity.Tag
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class RecordingTagRow(
    val recordingId: UUID,
    val id: UUID,
    val name: String,
    val color: String?,
)

/** A recording→tag assignment keyed by the tag's NAME, for re-linking across a REPLACE restore. */
data class RecordingTagNameLink(
    val recordingId: UUID,
    val tagName: String,
)

/** A profile→default-tag link keyed by the tag's NAME, for re-linking across a REPLACE restore. */
data class ProfileDefaultTagNameLink(
    val profileId: UUID,
    val tagName: String,
)

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<Tag>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAllTagsList(): List<Tag>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTag(id: UUID): Tag?

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun getTagByName(name: String): Tag?

    @Query("SELECT id FROM tags WHERE id IN (:ids)")
    suspend fun getExistingTagIds(ids: List<UUID>): List<UUID>

    @Query("SELECT COUNT(*) FROM recordings WHERE id = :recordingId")
    suspend fun getRecordingCount(recordingId: UUID): Int

    @Insert
    suspend fun insert(tag: Tag)

    @Insert
    suspend fun insertTags(tags: List<Tag>)

    @Update
    suspend fun update(tag: Tag): Int

    @Delete
    suspend fun delete(tag: Tag)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM tags")
    suspend fun deleteAllTags()

    @Query(
        """
        SELECT rt.recordingId AS recordingId, t.name AS tagName
        FROM recording_tags rt
        INNER JOIN tags t ON t.id = rt.tagId
    """,
    )
    suspend fun getRecordingTagLinksByName(): List<RecordingTagNameLink>

    @Query(
        """
        SELECT pdt.profileId AS profileId, t.name AS tagName
        FROM profile_default_tags pdt
        INNER JOIN tags t ON t.id = pdt.tagId
    """,
    )
    suspend fun getProfileDefaultTagLinksByName(): List<ProfileDefaultTagNameLink>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProfileDefaultTagLinks(links: List<ProfileDefaultTag>)

    /**
     * Backup restore, REPLACE semantics: clears every tag, then inserts the backup's tags.
     * Deleting a tag cascades its recording_tags and profile_default_tags rows — including
     * for tags the backup re-creates — so existing assignments are snapshotted by tag NAME
     * (the same natural key the MERGE path uses) before the delete and re-linked to the
     * inserted tags afterwards, all inside the one transaction. Only assignments to tags
     * that are NOT in the backup are dropped, exactly what the import UI promises.
     */
    @Transaction
    suspend fun replaceAllTags(tags: List<Tag>): BackupUpsertCounts {
        val recordingLinks = getRecordingTagLinksByName()
        val profileLinks = getProfileDefaultTagLinksByName()
        deleteAllTags()
        insertTags(tags)
        val tagIdsByName = tags.associateBy(Tag::name, Tag::id)
        val keptRecordingLinks =
            recordingLinks.mapNotNull { link ->
                tagIdsByName[link.tagName]?.let { tagId -> RecordingTag(link.recordingId, tagId) }
            }
        if (keptRecordingLinks.isNotEmpty()) {
            addTagsToRecording(keptRecordingLinks)
        }
        val keptProfileLinks =
            profileLinks.mapNotNull { link ->
                tagIdsByName[link.tagName]?.let { tagId -> ProfileDefaultTag(link.profileId, tagId) }
            }
        if (keptProfileLinks.isNotEmpty()) {
            insertProfileDefaultTagLinks(keptProfileLinks)
        }
        return BackupUpsertCounts(inserted = tags.size, updated = 0)
    }

    /**
     * Backup restore, MERGE semantics: upserts by tag name (the natural key). A name match
     * updates the existing row in place — the existing id is kept so recording and profile
     * references stay intact. New tags keep their backup id unless it collides with an
     * existing tag's id, in which case a fresh id is generated.
     */
    @Transaction
    suspend fun upsertTagsByName(tags: List<Tag>): BackupUpsertCounts {
        var inserted = 0
        var updated = 0
        for (tag in tags) {
            val existing = getTagByName(tag.name)
            if (existing != null) {
                update(existing.copy(color = tag.color))
                updated++
            } else {
                val safeId = if (getTag(tag.id) == null) tag.id else UUID.randomUUID()
                insert(tag.copy(id = safeId))
                inserted++
            }
        }
        return BackupUpsertCounts(inserted = inserted, updated = updated)
    }

    // Recording-Tag relationships

    @Query(
        """
        SELECT rt.recordingId AS recordingId, t.id AS id, t.name AS name, t.color AS color
        FROM recording_tags rt
        INNER JOIN tags t ON t.id = rt.tagId
        WHERE rt.recordingId IN (:recordingIds)
        ORDER BY rt.recordingId, t.name ASC
    """,
    )
    suspend fun getTagsForRecordingIds(recordingIds: List<UUID>): List<RecordingTagRow>

    @Query(
        """
        SELECT rt.recordingId AS recordingId, t.id AS id, t.name AS name, t.color AS color
        FROM recording_tags rt
        INNER JOIN tags t ON t.id = rt.tagId
        WHERE rt.recordingId IN (:recordingIds)
        ORDER BY rt.recordingId, t.name ASC
    """,
    )
    fun getTagsForRecordingIdsFlow(recordingIds: List<UUID>): Flow<List<RecordingTagRow>>

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN recording_tags rt ON t.id = rt.tagId
        WHERE rt.recordingId = :recordingId
        ORDER BY t.name ASC
    """,
    )
    fun getTagsForRecording(recordingId: UUID): Flow<List<Tag>>

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN recording_tags rt ON t.id = rt.tagId
        WHERE rt.recordingId = :recordingId
        ORDER BY t.name ASC
    """,
    )
    suspend fun getTagsForRecordingList(recordingId: UUID): List<Tag>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToRecording(recordingTag: RecordingTag)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagsToRecording(tags: List<RecordingTag>)

    @Delete
    suspend fun removeTagFromRecording(recordingTag: RecordingTag)

    @Query("DELETE FROM recording_tags WHERE recordingId = :recordingId")
    suspend fun removeAllTagsFromRecording(recordingId: UUID)

    @Query("DELETE FROM recording_tags WHERE recordingId = :recordingId AND tagId = :tagId")
    suspend fun removeTagFromRecordingById(
        recordingId: UUID,
        tagId: UUID,
    )

    @Transaction
    suspend fun setTagsForRecording(
        recordingId: UUID,
        tagIds: List<UUID>,
    ) {
        require(getRecordingCount(recordingId) > 0) {
            "Recording must exist before tags can be assigned"
        }
        val uniqueTagIds = validatedRecordingTagIds(tagIds)
        removeAllTagsFromRecording(recordingId)
        addTagsToRecording(uniqueTagIds.map { RecordingTag(recordingId, it) })
    }

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun getCount(): Int

    private suspend fun validatedRecordingTagIds(tagIds: List<UUID>): List<UUID> {
        val uniqueTagIds = tagIds.distinct()
        if (uniqueTagIds.isEmpty()) {
            return emptyList()
        }
        val existingTagIds = getExistingTagIds(uniqueTagIds).toSet()
        val missingTagIds = uniqueTagIds.filterNot(existingTagIds::contains)
        require(missingTagIds.isEmpty()) {
            "Recording tag IDs must reference existing tags: ${missingTagIds.joinToString()}"
        }
        return uniqueTagIds
    }
}
