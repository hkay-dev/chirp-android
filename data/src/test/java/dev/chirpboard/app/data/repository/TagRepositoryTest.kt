package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.TagDao
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

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
}
