package dev.chirpboard.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingRepositoryTransactionTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RecordingRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        repository =
            RecordingRepository(
                recordingDao = database.recordingDao(),
                transcriptDao = database.transcriptDao(),
                structuredOutcomeSnapshotDao = database.structuredOutcomeSnapshotDao(),
                database = database,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createRecordingWithTranscript_persistsBothRows() =
        runBlocking {
            val recording =
                Recording(
                    title = "Atomic write success",
                    audioPath = "",
                    source = RecordingSource.KEYBOARD,
                    status = RecordingStatus.COMPLETED,
                )
            val transcript =
                Transcript(
                    recordingId = recording.id,
                    rawText = "test transcript",
                )

            repository.createRecordingWithTranscript(recording, transcript)

            val persistedRecording = repository.getRecording(recording.id)
            val persistedTranscript = repository.getTranscript(recording.id)

            assertNotNull(persistedRecording)
            assertNotNull(persistedTranscript)
        }

    @Test
    fun createRecordingWithTranscript_rollsBackWhenTranscriptInsertFails() =
        runBlocking {
            val recording =
                Recording(
                    title = "Atomic write rollback",
                    audioPath = "",
                    source = RecordingSource.KEYBOARD,
                    status = RecordingStatus.COMPLETED,
                )

            // Force FK violation by using a different recordingId than the one inserted above
            val invalidTranscript =
                Transcript(
                    recordingId = UUID.randomUUID(),
                    rawText = "will fail",
                )

            var threw = false
            try {
                repository.createRecordingWithTranscript(recording, invalidTranscript)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                threw = true
            }

            assertTrue(threw)
            assertNull(repository.getRecording(recording.id))
            assertNull(repository.getTranscript(recording.id))
        }

    @Test
    fun saveTranscriptWithTiming_replacesExistingTimingRows() =
        runBlocking {
            val recording =
                Recording(
                    title = "Atomic timing replacement",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.COMPLETED,
                )
            repository.insert(recording)

            repository.saveTranscriptWithTiming(
                transcript = Transcript(recordingId = recording.id, rawText = "hello world"),
                timings = listOf(
                    TranscriptTiming(recording.id, 0, "hello", 0L, 100L),
                    TranscriptTiming(recording.id, 1, "world", 100L, 200L),
                ),
            )

            repository.saveTranscriptWithTiming(
                transcript = Transcript(recordingId = recording.id, rawText = "updated text"),
                timings = emptyList(),
            )

            val persistedTranscript = repository.getTranscript(recording.id)
            val persistedTimings = repository.getTranscriptTimings(recording.id)

            assertEquals("updated text", persistedTranscript?.rawText)
            assertTrue(persistedTimings.isEmpty())
        }

    @Test
    fun manualCorrectionPersistsUntilNewTranscriptCommitSucceeds() =
        runBlocking {
            val recording =
                Recording(
                    title = "Manual correction",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.COMPLETED,
                )
            repository.insert(recording)

            repository.saveTranscriptWithTiming(
                transcript = Transcript(recordingId = recording.id, rawText = "hello world"),
                timings = listOf(
                    TranscriptTiming(recording.id, 0, "hello", 0L, 100L),
                    TranscriptTiming(recording.id, 1, "world", 100L, 200L),
                ),
            )

            repository.saveManualCorrection(
                recordingId = recording.id,
                correctedText = "hello corrected world",
                sourceText = "hello world",
            )

            repository.updateStatus(recording.id, RecordingStatus.PENDING_TRANSCRIPTION)
            val pendingTranscript = repository.getTranscript(recording.id)
            assertEquals("hello corrected world", pendingTranscript?.effectiveText)
            assertEquals("hello world", pendingTranscript?.manualCorrectionSourceText)

            repository.updateStatusWithError(recording.id, RecordingStatus.FAILED, "transcription failed")
            val failedTranscript = repository.getTranscript(recording.id)
            assertEquals("hello corrected world", failedTranscript?.effectiveText)
            assertEquals("hello world", failedTranscript?.manualCorrectionSourceText)
            repository.saveTranscriptWithTiming(
                transcript = Transcript(recordingId = recording.id, rawText = "brand new raw"),
                timings = emptyList(),
            )

            val persistedTranscript = repository.getTranscript(recording.id)
            assertEquals("brand new raw", persistedTranscript?.rawText)
            assertNull(persistedTranscript?.manualCorrectionText)
            assertNull(persistedTranscript?.manualCorrectionSourceText)
            assertEquals("brand new raw", persistedTranscript?.effectiveText)
        }
}
