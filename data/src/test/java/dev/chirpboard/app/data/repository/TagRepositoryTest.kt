package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.RecordingTagRow
import dev.chirpboard.app.data.dao.TagDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

class TagRepositoryTest {
    private lateinit var tagDao: TagDao
    private lateinit var repository: TagRepository

    @Before
    fun setup() {
        tagDao = mockk(relaxed = true)
        repository = TagRepository(tagDao)
    }

    @Test
    fun `createTag inserts and returns new tag`() = runTest {
        val result = repository.createTag(name = "New Tag", color = "#FFFFFF")
        assertNotNull(result)
        assertEquals("New Tag", result.name)
        assertEquals("#FFFFFF", result.color)
        coVerify(exactly = 1) { tagDao.insert(any()) }
    }

    @Test
    fun `getTagsForRecordingIds chunks large ID lists`() = runTest {
        val recordingIds = List(1_005) { index ->
            UUID.nameUUIDFromBytes("recording-$index".toByteArray())
        }
        val tagId = UUID.randomUUID()
        coEvery { tagDao.getTagsForRecordingIds(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val batch = invocation.args[0] as List<UUID>
            batch.map { recordingId ->
                RecordingTagRow(
                    recordingId = recordingId,
                    id = tagId,
                    name = "Tag",
                    color = null,
                )
            }
        }

        val tagsByRecordingId = repository.getTagsForRecordingIds(recordingIds)

        assertEquals(recordingIds.size, tagsByRecordingId.size)
        coVerify(exactly = 2) { tagDao.getTagsForRecordingIds(any()) }
    }
}
