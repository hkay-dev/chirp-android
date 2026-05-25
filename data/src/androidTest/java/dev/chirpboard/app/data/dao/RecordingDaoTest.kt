package dev.chirpboard.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RecordingDao

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.recordingDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndGetRecording() = runTest {
        val recording =
            Recording(
                id = UUID.randomUUID(),
                title = "Morning memo",
                audioPath = "/tmp/test.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
            )
        dao.insert(recording)

        val loaded = dao.getRecording(recording.id)
        assertEquals("Morning memo", loaded?.title)
    }

    @Test
    fun getRecordingsByStatusFlow() = runTest {
        val pending =
            Recording(
                id = UUID.randomUUID(),
                title = "Pending",
                audioPath = "/tmp/pending.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
            )
        val completed =
            Recording(
                id = UUID.randomUUID(),
                title = "Done",
                audioPath = "/tmp/done.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.COMPLETED,
            )
        dao.insert(pending)
        dao.insert(completed)

        val pendingOnly = dao.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION).first()
        assertEquals(1, pendingOnly.size)
        assertEquals("Pending", pendingOnly.single().title)
    }

    @Test
    fun searchRecordings_excludesInProgressRows() = runTest {
        val inProgress =
            Recording(
                id = UUID.randomUUID(),
                title = "Live standup",
                audioPath = "/tmp/live.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.RECORDING,
            )
        val completed =
            Recording(
                id = UUID.randomUUID(),
                title = "Live standup notes",
                audioPath = "/tmp/done.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.COMPLETED,
            )
        dao.insert(inProgress)
        dao.insert(completed)

        val results = dao.searchRecordings("Live").first()

        assertEquals(1, results.size)
        assertEquals(completed.id, results.single().id)
    }
}
