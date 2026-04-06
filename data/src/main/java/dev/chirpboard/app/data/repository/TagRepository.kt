package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.TagDao
import dev.chirpboard.app.data.entity.RecordingTag
import dev.chirpboard.app.data.entity.Tag
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing tags and recording-tag relationships.
 */
@Singleton
class TagRepository
    @Inject
    constructor(
        private val tagDao: TagDao,
    ) {
        fun getAllTags(): Flow<List<Tag>> = tagDao.getAllTags()

        suspend fun getAllTagsList(): List<Tag> = tagDao.getAllTagsList()

        suspend fun getTag(id: UUID): Tag? = tagDao.getTag(id)

        suspend fun getTagByName(name: String): Tag? = tagDao.getTagByName(name)

        suspend fun createTag(
            name: String,
            color: String? = null,
        ): Tag {
            val tag = Tag(name = name, color = color)
            tagDao.insert(tag)
            return tag
        }

        suspend fun insert(tag: Tag) = tagDao.insert(tag)

        suspend fun update(tag: Tag) = tagDao.update(tag)

        suspend fun delete(tag: Tag) = tagDao.delete(tag)

        suspend fun deleteById(id: UUID) = tagDao.deleteById(id)

        fun getTagsForRecording(recordingId: UUID): Flow<List<Tag>> = tagDao.getTagsForRecording(recordingId)

        suspend fun getTagsForRecordingIds(recordingIds: List<UUID>): Map<UUID, List<Tag>> =
            if (recordingIds.isEmpty()) {
                emptyMap()
            } else {
                tagDao
                    .getTagsForRecordingIds(recordingIds)
                    .groupBy(
                        keySelector = { it.recordingId },
                        valueTransform = { row ->
                            Tag(
                                id = row.id,
                                name = row.name,
                                color = row.color,
                            )
                        },
                    )
            }

        suspend fun getTagsForRecordingList(recordingId: UUID): List<Tag> = tagDao.getTagsForRecordingList(recordingId)

        suspend fun addTagToRecording(
            recordingId: UUID,
            tagId: UUID,
        ) = tagDao.addTagToRecording(RecordingTag(recordingId, tagId))

        suspend fun removeTagFromRecording(
            recordingId: UUID,
            tagId: UUID,
        ) = tagDao.removeTagFromRecordingById(recordingId, tagId)

        suspend fun setTagsForRecording(
            recordingId: UUID,
            tagIds: List<UUID>,
        ) = tagDao.setTagsForRecording(recordingId, tagIds)

        suspend fun removeAllTagsFromRecording(recordingId: UUID) = tagDao.removeAllTagsFromRecording(recordingId)

        suspend fun getCount(): Int = tagDao.getCount()
    }
