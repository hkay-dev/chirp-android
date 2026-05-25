package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.RecordingDao
import dev.chirpboard.app.data.dao.StructuredOutcomeSnapshotDao
import dev.chirpboard.app.data.dao.TranscriptDao
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.toEntity
import dev.chirpboard.app.data.entity.toModel
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.StructuredOutcomeGenerationStatus
import dev.chirpboard.app.data.model.StructuredOutcomeSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RecordingRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var recordingDao: RecordingDao
    private lateinit var transcriptDao: TranscriptDao
    private lateinit var structuredOutcomeSnapshotDao: StructuredOutcomeSnapshotDao
    private lateinit var repository: RecordingRepository

    @Before
    fun setup() {
        database = mockk(relaxed = true)
        recordingDao = mockk(relaxed = true)
        transcriptDao = mockk(relaxed = true)
        structuredOutcomeSnapshotDao = mockk(relaxed = true)
        repository = RecordingRepository(database, recordingDao, transcriptDao, structuredOutcomeSnapshotDao)
    }

    @Test
    fun `createRecording inserts and returns new recording`() = runTest {
        val result = repository.createRecording(
            title = "New Recording",
            audioPath = "/path/to/audio",
            source = RecordingSource.APP,
            durationMs = 1000L
        )
        assertNotNull(result)
        assertEquals("New Recording", result.title)
        assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, result.status)
        assertEquals(1000L, result.durationMs)
        coVerify(exactly = 1) { recordingDao.insert(any()) }
    }

    @Test
    fun `saveStructuredOutcomeSuccess replaces snapshot payload`() = runTest {
        val recordingId = UUID.randomUUID()

        repository.saveStructuredOutcomeSuccess(
            recordingId = recordingId,
            sourceTranscriptRevision = "rev-2",
            tasks = listOf("Review draft"),
            decisions = listOf("Ship Friday"),
            followUps = listOf("Ping legal"),
        )

        coVerify(exactly = 1) {
            structuredOutcomeSnapshotDao.insert(
                match { snapshot ->
                    snapshot.recordingId == recordingId &&
                        snapshot.sourceTranscriptRevision == "rev-2" &&
                        snapshot.generationStatus == StructuredOutcomeGenerationStatus.READY &&
                        snapshot.generatedAt != null &&
                        snapshot.failureMessage == null &&
                        snapshot.toModel().tasks == listOf("Review draft") &&
                        snapshot.toModel().decisions == listOf("Ship Friday") &&
                        snapshot.toModel().followUps == listOf("Ping legal")
                },
            )
        }
    }

    @Test
    fun `saveStructuredOutcomeFailure keeps ready payload when snapshot already exists`() = runTest {
        val recordingId = UUID.randomUUID()
        val existing =
            StructuredOutcomeSnapshot(
                recordingId = recordingId,
                sourceTranscriptRevision = "rev-1",
                generationStatus = StructuredOutcomeGenerationStatus.READY,
                generatedAt = java.util.Date(1_000L),
                lastAttemptedAt = java.util.Date(1_000L),
                tasks = listOf("Review draft"),
            ).toEntity()

        coEvery { structuredOutcomeSnapshotDao.getSnapshot(recordingId) } returns existing

        repository.saveStructuredOutcomeFailure(
            recordingId = recordingId,
            sourceTranscriptRevision = "rev-2",
            failureMessage = "Schema parse failed",
        )

        coVerify(exactly = 1) { structuredOutcomeSnapshotDao.getSnapshot(recordingId) }
        coVerify(exactly = 1) {
            structuredOutcomeSnapshotDao.insert(
                match { snapshot ->
                    snapshot.recordingId == recordingId &&
                        snapshot.sourceTranscriptRevision == "rev-1" &&
                        snapshot.generationStatus == StructuredOutcomeGenerationStatus.FAILED &&
                        snapshot.generatedAt?.time == 1_000L &&
                        snapshot.failureMessage == "Schema parse failed" &&
                        snapshot.toModel().tasks == listOf("Review draft")
                },
            )
        }
    }
}
