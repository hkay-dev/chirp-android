package dev.parakeeboard.app.data.dao

import androidx.room.*
import dev.parakeeboard.app.data.entity.RecordingTag
import dev.parakeeboard.app.data.entity.Tag
import kotlinx.coroutines.flow.Flow
import java.util.UUID

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
    
    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN recording_tags rt ON t.id = rt.tagId
        WHERE rt.recordingId = :recordingId
        ORDER BY t.name ASC
    """)
    fun getTagsForRecording(recordingId: UUID): Flow<List<Tag>>
    
    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN recording_tags rt ON t.id = rt.tagId
        WHERE rt.recordingId = :recordingId
        ORDER BY t.name ASC
    """)
    suspend fun getTagsForRecordingList(recordingId: UUID): List<Tag>
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToRecording(recordingTag: RecordingTag)
    
    @Delete
    suspend fun removeTagFromRecording(recordingTag: RecordingTag)
    
    @Query("DELETE FROM recording_tags WHERE recordingId = :recordingId")
    suspend fun removeAllTagsFromRecording(recordingId: UUID)
    
    @Query("DELETE FROM recording_tags WHERE recordingId = :recordingId AND tagId = :tagId")
    suspend fun removeTagFromRecordingById(recordingId: UUID, tagId: UUID)
    
    @Transaction
    suspend fun setTagsForRecording(recordingId: UUID, tagIds: List<UUID>) {
        removeAllTagsFromRecording(recordingId)
        tagIds.forEach { tagId ->
            addTagToRecording(RecordingTag(recordingId, tagId))
        }
    }
    
    @Query("SELECT COUNT(*) FROM tags")
    suspend fun getCount(): Int
}
