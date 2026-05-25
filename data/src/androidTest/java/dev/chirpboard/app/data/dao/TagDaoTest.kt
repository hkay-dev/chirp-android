package dev.chirpboard.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Tag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TagDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: TagDao

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.tagDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndGetTagByName() = runTest {
        val tag = Tag(id = UUID.randomUUID(), name = "Meetings", color = "#FF0000")
        dao.insert(tag)

        val loaded = dao.getTagByName("Meetings")
        assertEquals(tag.id, loaded?.id)
        assertEquals("#FF0000", loaded?.color)
    }

    @Test
    fun getAllTagsFlow() = runTest {
        dao.insert(Tag(id = UUID.randomUUID(), name = "Alpha"))
        dao.insert(Tag(id = UUID.randomUUID(), name = "Beta"))

        val tags = dao.getAllTags().first()
        assertEquals(2, tags.size)
        assertEquals("Alpha", tags[0].name)
        assertEquals("Beta", tags[1].name)
    }
}
