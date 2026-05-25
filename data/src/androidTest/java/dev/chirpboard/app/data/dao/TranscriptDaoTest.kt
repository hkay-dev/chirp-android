package dev.chirpboard.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranscriptDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var recordingDao: RecordingDao
    private lateinit var transcriptDao: TranscriptDao

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
        recordingDao = database.recordingDao()
        transcriptDao = database.transcriptDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndGetTranscriptForRecording() = runTest {
        val recordingId = UUID.randomUUID()
        recordingDao.insert(
            Recording(
                id = recordingId,
                title = "Interview",
                audioPath = "/tmp/interview.m4a",
                source = RecordingSource.APP,
            ),
        )

        val transcript =
            Transcript(
                recordingId = recordingId,
                rawText = "Hello world",
                summary = "Greeting",
            )
        transcriptDao.insert(transcript)

        val loaded = transcriptDao.getTranscript(recordingId)
        assertEquals("Hello world", loaded?.rawText)
        assertEquals("Greeting", loaded?.summary)
    }

    @Test
    fun getTranscriptFlow() = runTest {
        val recordingId = UUID.randomUUID()
        recordingDao.insert(
            Recording(
                id = recordingId,
                title = "Flow test",
                audioPath = "/tmp/flow.m4a",
                source = RecordingSource.APP,
            ),
        )
        transcriptDao.insert(
            Transcript(
                recordingId = recordingId,
                rawText = "Streaming text",
            ),
        )

        val loaded = transcriptDao.getTranscriptFlow(recordingId).first()
        assertEquals("Streaming text", loaded?.rawText)
    }
}
