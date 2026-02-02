package dev.parakeeboard.app.data.repository

import dev.parakeeboard.app.data.dao.TagDao
import dev.parakeeboard.app.data.entity.RecordingTag
import dev.parakeeboard.app.data.entity.Tag
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing tags and recording-tag relationships.
 */
@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao
) {
    
    /** Get all tags ordered by name */
    fun getAllTags(): Flow<List<Tag>> = tagDao.getAllTags()
    
    /** Get all tags as a list */
    suspend fun getAllTagsList(): List<Tag> = tagDao.getAllTagsList()
    
    /** Get a single tag by ID */
    suspend fun getTag(id: UUID): Tag? = tagDao.getTag(id)
    
    /** Get a tag by name */
    suspend fun getTagByName(name: String): Tag? = tagDao.getTagByName(name)
    
    /** Create a new tag */
    suspend fun createTag(name: String, color: String? = null): Tag {
        val tag = Tag(name = name, color = color)
        tagDao.insert(tag)
        return tag
    }
    
    /** Insert an existing tag */
    suspend fun insert(tag: Tag) = tagDao.insert(tag)
    
    /** Update a tag */
    suspend fun update(tag: Tag) = tagDao.update(tag)
    
    /** Delete a tag */
    suspend fun delete(tag: Tag) = tagDao.delete(tag)
    
    /** Delete a tag by ID */
    suspend fun deleteById(id: UUID) = tagDao.deleteById(id)
    
    // Recording-Tag relationships
    
    /** Get tags for a specific recording */
    fun getTagsForRecording(recordingId: UUID): Flow<List<Tag>> =
        tagDao.getTagsForRecording(recordingId)
    
    /** Get tags for a specific recording as list */
    suspend fun getTagsForRecordingList(recordingId: UUID): List<Tag> =
        tagDao.getTagsForRecordingList(recordingId)
    
    /** Add a tag to a recording */
    suspend fun addTagToRecording(recordingId: UUID, tagId: UUID) =
        tagDao.addTagToRecording(RecordingTag(recordingId, tagId))
    
    /** Remove a tag from a recording */
    suspend fun removeTagFromRecording(recordingId: UUID, tagId: UUID) =
        tagDao.removeTagFromRecordingById(recordingId, tagId)
    
    /** Set all tags for a recording (replaces existing) */
    suspend fun setTagsForRecording(recordingId: UUID, tagIds: List<UUID>) =
        tagDao.setTagsForRecording(recordingId, tagIds)
    
    /** Remove all tags from a recording */
    suspend fun removeAllTagsFromRecording(recordingId: UUID) =
        tagDao.removeAllTagsFromRecording(recordingId)
    
    /** Get tag count */
    suspend fun getCount(): Int = tagDao.getCount()
}
