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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: Tag)

    @Update
    suspend fun update(tag: Tag)

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
        removeAllTagsFromRecording(recordingId)
        addTagsToRecording(tagIds.map { RecordingTag(recordingId, it) })
    }

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun getCount(): Int
}
