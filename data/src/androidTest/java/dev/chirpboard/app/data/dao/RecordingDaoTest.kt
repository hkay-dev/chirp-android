package dev.chirpboard.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RecordingDao
    private lateinit var transcriptDao: TranscriptDao

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.recordingDao()
        transcriptDao = database.transcriptDao()
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

        val results = dao.searchRecordings("%Live%", matchQuery = "live*", limit = 10).first()

        assertEquals(1, results.size)
        assertEquals(completed.id, results.single().id)
    }

    @Test
    fun updateStatusWithTranscriptionToken_claimsAndStampsTokenWhenNoTokenExpected() = runTest {
        val recording = guardedRecording(status = RecordingStatus.FAILED, token = "stale-token")
        dao.insert(recording)

        val updated =
            dao.updateStatusWithTranscriptionToken(
                id = recording.id,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                errorMessage = null,
                executionToken = "fresh-token",
                allowedCurrentStatuses =
                    listOf(
                        RecordingStatus.PENDING_TRANSCRIPTION,
                        RecordingStatus.TRANSCRIBING,
                        RecordingStatus.ENHANCING,
                        RecordingStatus.FAILED,
                    ),
                expectedExecutionToken = null,
            )

        assertEquals(1, updated)
        val loaded = dao.getRecording(recording.id)
        assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, loaded?.status)
        assertEquals("fresh-token", loaded?.transcriptionExecutionToken)
    }

    @Test
    fun updateStatusWithTranscriptionToken_rejectsStaleExpectedToken() = runTest {
        val recording = guardedRecording(status = RecordingStatus.TRANSCRIBING, token = "current-token")
        dao.insert(recording)

        val updated =
            dao.updateStatusWithTranscriptionToken(
                id = recording.id,
                status = RecordingStatus.COMPLETED,
                errorMessage = null,
                executionToken = null,
                allowedCurrentStatuses = listOf(RecordingStatus.TRANSCRIBING),
                expectedExecutionToken = "stale-token",
            )

        assertEquals(0, updated)
        val loaded = dao.getRecording(recording.id)
        assertEquals(RecordingStatus.TRANSCRIBING, loaded?.status)
        assertEquals("current-token", loaded?.transcriptionExecutionToken)
    }

    @Test
    fun updateStatusWithTranscriptionToken_rejectsExpectedTokenWhenRowTokenIsNull() = runTest {
        val recording = guardedRecording(status = RecordingStatus.TRANSCRIBING, token = null)
        dao.insert(recording)

        val updated =
            dao.updateStatusWithTranscriptionToken(
                id = recording.id,
                status = RecordingStatus.COMPLETED,
                errorMessage = null,
                executionToken = null,
                allowedCurrentStatuses = listOf(RecordingStatus.TRANSCRIBING),
                expectedExecutionToken = "some-token",
            )

        assertEquals(0, updated)
        assertEquals(RecordingStatus.TRANSCRIBING, dao.getRecording(recording.id)?.status)
    }

    @Test
    fun updateStatusWithTranscriptionToken_rejectsCompletedRegressionAttempt() = runTest {
        val recording = guardedRecording(status = RecordingStatus.COMPLETED, token = null)
        dao.insert(recording)

        val updated =
            dao.updateStatusWithTranscriptionToken(
                id = recording.id,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                errorMessage = null,
                executionToken = "fresh-token",
                allowedCurrentStatuses =
                    listOf(
                        RecordingStatus.PENDING_TRANSCRIPTION,
                        RecordingStatus.TRANSCRIBING,
                        RecordingStatus.ENHANCING,
                        RecordingStatus.FAILED,
                    ),
                expectedExecutionToken = null,
            )

        assertEquals(0, updated)
        val loaded = dao.getRecording(recording.id)
        assertEquals(RecordingStatus.COMPLETED, loaded?.status)
        assertEquals(null, loaded?.transcriptionExecutionToken)
    }

    @Test
    fun updateStatusWithTranscriptionToken_bindsMultiStatusListAcrossRows() = runTest {
        val transcribing = guardedRecording(status = RecordingStatus.TRANSCRIBING, token = null)
        val failed = guardedRecording(status = RecordingStatus.FAILED, token = null)
        val completed = guardedRecording(status = RecordingStatus.COMPLETED, token = null)
        dao.insert(transcribing)
        dao.insert(failed)
        dao.insert(completed)

        val allowed = listOf(RecordingStatus.TRANSCRIBING, RecordingStatus.FAILED)
        val results =
            listOf(transcribing, failed, completed).map { recording ->
                dao.updateStatusWithTranscriptionToken(
                    id = recording.id,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = null,
                    executionToken = "fresh-token",
                    allowedCurrentStatuses = allowed,
                    expectedExecutionToken = null,
                )
            }

        assertEquals(listOf(1, 1, 0), results)
        assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, dao.getRecording(transcribing.id)?.status)
        assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, dao.getRecording(failed.id)?.status)
        assertEquals(RecordingStatus.COMPLETED, dao.getRecording(completed.id)?.status)
    }

    @Test
    fun updateStatusForTranscriptionExecution_appliesWhenStatusAndTokenMatch() = runTest {
        val recording = guardedRecording(status = RecordingStatus.PENDING_TRANSCRIPTION, token = "run-token")
        dao.insert(recording)

        val updated =
            dao.updateStatusForTranscriptionExecution(
                id = recording.id,
                expectedStatus = RecordingStatus.PENDING_TRANSCRIPTION,
                executionToken = "run-token",
                newStatus = RecordingStatus.TRANSCRIBING,
                errorMessage = null,
            )

        assertEquals(1, updated)
        assertEquals(RecordingStatus.TRANSCRIBING, dao.getRecording(recording.id)?.status)
    }

    @Test
    fun updateStatusForTranscriptionExecution_rejectsTokenMismatch() = runTest {
        val recording = guardedRecording(status = RecordingStatus.PENDING_TRANSCRIPTION, token = "run-token")
        dao.insert(recording)

        val updated =
            dao.updateStatusForTranscriptionExecution(
                id = recording.id,
                expectedStatus = RecordingStatus.PENDING_TRANSCRIPTION,
                executionToken = "other-token",
                newStatus = RecordingStatus.TRANSCRIBING,
                errorMessage = null,
            )

        assertEquals(0, updated)
        assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, dao.getRecording(recording.id)?.status)
    }

    @Test
    fun updateStatusForTranscriptionExecution_rejectsStatusMismatch() = runTest {
        val recording = guardedRecording(status = RecordingStatus.COMPLETED, token = "run-token")
        dao.insert(recording)

        val updated =
            dao.updateStatusForTranscriptionExecution(
                id = recording.id,
                expectedStatus = RecordingStatus.TRANSCRIBING,
                executionToken = "run-token",
                newStatus = RecordingStatus.FAILED,
                errorMessage = "should not apply",
            )

        assertEquals(0, updated)
        val loaded = dao.getRecording(recording.id)
        assertEquals(RecordingStatus.COMPLETED, loaded?.status)
        assertEquals(null, loaded?.errorMessage)
    }

    @Test
    fun finalizeInProgressIfCurrent_nonNullAudioPathOverwritesColumn() = runTest {
        val recording =
            Recording(
                id = UUID.randomUUID(),
                title = "In progress",
                audioPath = "/tmp/segment-000.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.RECORDING,
            )
        dao.insert(recording)

        val updated =
            dao.finalizeInProgressIfCurrent(
                id = recording.id,
                durationMs = 1_234L,
                title = "Finalized",
                audioPath = "/tmp/export.m4a",
            )

        assertEquals(1, updated)
        val loaded = dao.getRecording(recording.id)
        // The CASE WHEN must point the row at the real exported file, never leaving it
        // referencing a temp segment path that cleanup may later delete.
        assertEquals("/tmp/export.m4a", loaded?.audioPath)
        assertEquals("Finalized", loaded?.title)
        assertEquals(1_234L, loaded?.durationMs)
        assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, loaded?.status)
    }

    @Test
    fun finalizeInProgressIfCurrent_nullAudioPathKeepsExistingValue() = runTest {
        val recording =
            Recording(
                id = UUID.randomUUID(),
                title = "In progress",
                audioPath = "/tmp/original.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.RECORDING,
            )
        dao.insert(recording)

        val updated =
            dao.finalizeInProgressIfCurrent(
                id = recording.id,
                durationMs = 2_345L,
                title = null,
                audioPath = null,
            )

        assertEquals(1, updated)
        val loaded = dao.getRecording(recording.id)
        // Null arguments are "keep the current value" for both CASE WHEN columns.
        assertEquals("/tmp/original.m4a", loaded?.audioPath)
        assertEquals("In progress", loaded?.title)
        assertEquals(2_345L, loaded?.durationMs)
        assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, loaded?.status)
    }

    @Test
    fun searchRecordings_appliesLimitAndStableOrdering() = runTest {
        val ids =
            listOf(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
            )
        ids.forEach { id ->
            dao.insert(
                Recording(
                    id = id,
                    title = "Match note",
                    audioPath = "/tmp/$id.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.COMPLETED,
                    createdAt = Date(1_000L),
                ),
            )
        }

        val results = dao.searchRecordings("%Match%", matchQuery = "match*", limit = 2).first()

        assertEquals(2, results.size)
        assertEquals(ids.take(2), results.map { it.id })
    }

    @Test
    fun searchRecordings_matchesTranscriptTextThroughTheFtsIndex() = runTest {
        val recordingId = UUID.randomUUID()
        dao.insert(
            Recording(
                id = recordingId,
                title = "Untitled recording",
                audioPath = "/tmp/fts.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.COMPLETED,
            ),
        )
        transcriptDao.insert(
            Transcript(
                recordingId = recordingId,
                rawText = "the quarterly budget review",
            ),
        )

        // Prefix query on a word that appears only in the transcript, never in the title.
        val results = dao.searchRecordings("%quarter%", matchQuery = "quarter*", limit = 10).first()

        assertEquals(listOf(recordingId), results.map { it.id })
    }

    @Test
    fun searchRecordings_reindexesEditedTranscriptText() = runTest {
        val recordingId = UUID.randomUUID()
        dao.insert(
            Recording(
                id = recordingId,
                title = "Untitled recording",
                audioPath = "/tmp/edit.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.COMPLETED,
            ),
        )
        val transcript =
            Transcript(
                recordingId = recordingId,
                rawText = "original wording",
            )
        transcriptDao.insert(transcript)
        transcriptDao.updateManualCorrection(
            recordingId = recordingId,
            manualCorrectionText = "corrected wording",
            manualCorrectionSourceText = "original wording",
        )

        // AFTER_UPDATE reindexed the row, so the correction is searchable.
        assertEquals(
            listOf(recordingId),
            dao.searchRecordings("%zzz%", matchQuery = "corrected*", limit = 10).first().map { it.id },
        )

        // A REPLACE insert on the same primary key drops the old indexed row instead of
        // leaving the superseded text matchable.
        transcriptDao.insert(transcript.copy(rawText = "rewritten wording", manualCorrectionText = null))
        assertEquals(
            emptyList<UUID>(),
            dao.searchRecordings("%zzz%", matchQuery = "corrected*", limit = 10).first().map { it.id },
        )
        assertEquals(
            listOf(recordingId),
            dao.searchRecordings("%zzz%", matchQuery = "rewritten*", limit = 10).first().map { it.id },
        )
    }

    @Test
    fun searchRecordingsByTitle_ignoresTranscriptText() = runTest {
        val recordingId = UUID.randomUUID()
        dao.insert(
            Recording(
                id = recordingId,
                title = "Untitled recording",
                audioPath = "/tmp/title.m4a",
                source = RecordingSource.APP,
                status = RecordingStatus.COMPLETED,
            ),
        )
        transcriptDao.insert(
            Transcript(
                recordingId = recordingId,
                rawText = "budget review",
            ),
        )

        assertEquals(emptyList<UUID>(), dao.searchRecordingsByTitle("%budget%", limit = 10).first().map { it.id })
        assertEquals(listOf(recordingId), dao.searchRecordingsByTitle("%titled%", limit = 10).first().map { it.id })
    }

    private fun guardedRecording(
        status: RecordingStatus,
        token: String?,
    ): Recording {
        val id = UUID.randomUUID()
        return Recording(
            id = id,
            title = "Guarded",
            audioPath = "/tmp/$id.m4a",
            source = RecordingSource.APP,
            status = status,
            transcriptionExecutionToken = token,
        )
    }
}
