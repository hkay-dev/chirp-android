package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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

    @Update
    suspend fun update(tag: Tag): Int

    @Delete
    suspend fun delete(tag: Tag)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: UUID)

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
