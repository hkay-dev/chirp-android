package dev.chirpboard.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.ProfileDefaultTag
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.RecordingTag
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun replaceAllTags_survivesTheRealFkCascadeForTagsKeptByName() = runTest {
        // Regression: the tags delete CASCADE-drops every recording_tags and
        // profile_default_tags row, even for tags the backup re-creates. The REPLACE
        // restore must re-link assignments by tag name inside the same transaction.
        val recording = Recording(title = "r", audioPath = "/tmp/r.m4a", source = RecordingSource.APP)
        database.recordingDao().insert(recording)
        val profile = Profile(name = "Meetings")
        database.profileDao().insert(profile)

        val kept = Tag(name = "work", color = "#111111")
        val dropped = Tag(name = "scratch")
        dao.insert(kept)
        dao.insert(dropped)
        dao.addTagToRecording(RecordingTag(recording.id, kept.id))
        dao.addTagToRecording(RecordingTag(recording.id, dropped.id))
        database.profileDao().insertDefaultTags(listOf(ProfileDefaultTag(profile.id, kept.id)))

        // Cross-device restore: the backup's "work" tag carries a DIFFERENT id.
        val incoming = Tag(name = "work", color = "#999999")
        dao.replaceAllTags(listOf(incoming))

        val recordingTags = dao.getTagsForRecordingList(recording.id)
        assertEquals(listOf(incoming.id), recordingTags.map(Tag::id))
        val defaultTags = database.profileDao().getDefaultTagIds(profile.id)
        assertEquals(listOf(incoming.id), defaultTags)
    }

    @Test
    fun replaceAllTags_dropsAssignmentsOnlyForTagsAbsentFromTheBackup() = runTest {
        val recording = Recording(title = "r", audioPath = "/tmp/r.m4a", source = RecordingSource.APP)
        database.recordingDao().insert(recording)
        val removed = Tag(name = "gone")
        dao.insert(removed)
        dao.addTagToRecording(RecordingTag(recording.id, removed.id))

        dao.replaceAllTags(listOf(Tag(name = "unrelated")))

        assertTrue(dao.getTagsForRecordingList(recording.id).isEmpty())
    }
}
