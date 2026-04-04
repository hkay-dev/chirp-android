package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.TagDao
import dev.chirpboard.app.data.entity.RecordingTag
import dev.chirpboard.app.data.entity.Tag
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
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
    fun `getAllTags returns flow from dao`() = runTest {
        val expected = listOf(Tag(id = UUID.randomUUID(), name = "Tag1"))
        coEvery { tagDao.getAllTags() } returns flowOf(expected)
        val result = repository.getAllTags().first()
        assertEquals(expected, result)
    }

    @Test
    fun `getAllTagsList returns list from dao`() = runTest {
        val expected = listOf(Tag(id = UUID.randomUUID(), name = "Tag1"))
        coEvery { tagDao.getAllTagsList() } returns expected
        val result = repository.getAllTagsList()
        assertEquals(expected, result)
    }

    @Test
    fun `getTag returns correct tag`() = runTest {
        val id = UUID.randomUUID()
        val expected = Tag(id = id, name = "Tag1")
        coEvery { tagDao.getTag(id) } returns expected
        val result = repository.getTag(id)
        assertEquals(expected, result)
    }

    @Test
    fun `getTagByName returns correct tag`() = runTest {
        val name = "Tag1"
        val expected = Tag(id = UUID.randomUUID(), name = name)
        coEvery { tagDao.getTagByName(name) } returns expected
        val result = repository.getTagByName(name)
        assertEquals(expected, result)
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
    fun `insert delegates to dao`() = runTest {
        val tag = Tag(id = UUID.randomUUID(), name = "Tag1")
        repository.insert(tag)
        coVerify(exactly = 1) { tagDao.insert(tag) }
    }

    @Test
    fun `update delegates to dao`() = runTest {
        val tag = Tag(id = UUID.randomUUID(), name = "Tag1")
        repository.update(tag)
        coVerify(exactly = 1) { tagDao.update(tag) }
    }

    @Test
    fun `delete delegates to dao`() = runTest {
        val tag = Tag(id = UUID.randomUUID(), name = "Tag1")
        repository.delete(tag)
        coVerify(exactly = 1) { tagDao.delete(tag) }
    }

    @Test
    fun `deleteById delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.deleteById(id)
        coVerify(exactly = 1) { tagDao.deleteById(id) }
    }

    @Test
    fun `getTagsForRecording returns flow from dao`() = runTest {
        val id = UUID.randomUUID()
        val expected = listOf(Tag(id = UUID.randomUUID(), name = "Tag1"))
        coEvery { tagDao.getTagsForRecording(id) } returns flowOf(expected)
        val result = repository.getTagsForRecording(id).first()
        assertEquals(expected, result)
    }

    @Test
    fun `getTagsForRecordingList returns list from dao`() = runTest {
        val id = UUID.randomUUID()
        val expected = listOf(Tag(id = UUID.randomUUID(), name = "Tag1"))
        coEvery { tagDao.getTagsForRecordingList(id) } returns expected
        val result = repository.getTagsForRecordingList(id)
        assertEquals(expected, result)
    }

    @Test
    fun `addTagToRecording delegates to dao`() = runTest {
        val recordingId = UUID.randomUUID()
        val tagId = UUID.randomUUID()
        repository.addTagToRecording(recordingId, tagId)
        coVerify(exactly = 1) { tagDao.addTagToRecording(RecordingTag(recordingId, tagId)) }
    }

    @Test
    fun `removeTagFromRecording delegates to dao`() = runTest {
        val recordingId = UUID.randomUUID()
        val tagId = UUID.randomUUID()
        repository.removeTagFromRecording(recordingId, tagId)
        coVerify(exactly = 1) { tagDao.removeTagFromRecordingById(recordingId, tagId) }
    }

    @Test
    fun `setTagsForRecording delegates to dao`() = runTest {
        val recordingId = UUID.randomUUID()
        val tagIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        repository.setTagsForRecording(recordingId, tagIds)
        coVerify(exactly = 1) { tagDao.setTagsForRecording(recordingId, tagIds) }
    }

    @Test
    fun `removeAllTagsFromRecording delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.removeAllTagsFromRecording(id)
        coVerify(exactly = 1) { tagDao.removeAllTagsFromRecording(id) }
    }

    @Test
    fun `getCount delegates to dao`() = runTest {
        coEvery { tagDao.getCount() } returns 5
        val result = repository.getCount()
        assertEquals(5, result)
    }
}
