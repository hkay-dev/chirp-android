package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.RecordingDao
import dev.chirpboard.app.data.dao.StructuredOutcomeSnapshotDao
import dev.chirpboard.app.data.dao.TranscriptDao
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.toEntity
import dev.chirpboard.app.data.entity.toModel
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.StructuredOutcomeGenerationStatus
import dev.chirpboard.app.data.model.StructuredOutcomeSnapshot
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
    fun `getAllRecordings returns flow from dao`() = runTest {
        val expected = listOf(Recording(id = UUID.randomUUID(), title = "Test", audioPath = "", source = RecordingSource.APP))
        coEvery { recordingDao.getAllRecordings() } returns flowOf(expected)
        val result = repository.getAllRecordings().first()
        assertEquals(expected, result.value)
    }

    @Test
    fun `getRecording returns correct recording`() = runTest {
        val id = UUID.randomUUID()
        val expected = Recording(id = id, title = "Test", audioPath = "", source = RecordingSource.APP)
        coEvery { recordingDao.getRecording(id) } returns expected
        val result = repository.getRecording(id)
        assertEquals(expected, result)
        coVerify(exactly = 1) { recordingDao.getRecording(id) }
    }

    @Test
    fun `getRecordingFlow returns flow from dao`() = runTest {
        val id = UUID.randomUUID()
        val expected = Recording(id = id, title = "Test", audioPath = "", source = RecordingSource.APP)
        coEvery { recordingDao.getRecordingFlow(id) } returns flowOf(expected)
        val result = repository.getRecordingFlow(id).first()
        assertEquals(expected, result.value)
    }

    @Test
    fun `getRecordingsByStatus returns flow from dao`() = runTest {
        val expected = listOf(Recording(id = UUID.randomUUID(), title = "Test", audioPath = "", source = RecordingSource.APP))
        coEvery { recordingDao.getRecordingsByStatus(RecordingStatus.COMPLETED) } returns flowOf(expected)
        val result = repository.getRecordingsByStatus(RecordingStatus.COMPLETED).first()
        assertEquals(expected, result.value)
    }

    @Test
    fun `getPendingRecordings returns list from dao`() = runTest {
        val expected = listOf(Recording(id = UUID.randomUUID(), title = "Test", audioPath = "", source = RecordingSource.APP))
        coEvery { recordingDao.getRecordingsByStatuses(any()) } returns expected
        val result = repository.getPendingRecordings()
        assertEquals(expected, result)
    }

    @Test
    fun `searchRecordings returns flow from dao`() = runTest {
        val expected = listOf(Recording(id = UUID.randomUUID(), title = "Test", audioPath = "", source = RecordingSource.APP))
        coEvery { recordingDao.searchRecordings("Test") } returns flowOf(expected)
        val result = repository.searchRecordings("Test").first()
        assertEquals(expected, result.value)
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
    fun `insert delegates to dao`() = runTest {
        val recording = Recording(id = UUID.randomUUID(), title = "Test", audioPath = "", source = RecordingSource.APP)
        repository.insert(recording)
        coVerify(exactly = 1) { recordingDao.insert(recording) }
    }

    @Test
    fun `update delegates to dao`() = runTest {
        val recording = Recording(id = UUID.randomUUID(), title = "Test", audioPath = "", source = RecordingSource.APP)
        repository.update(recording)
        coVerify(exactly = 1) { recordingDao.update(recording) }
    }

    @Test
    fun `updateStatus delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.updateStatus(id, RecordingStatus.COMPLETED)
        coVerify(exactly = 1) { recordingDao.updateStatus(id, RecordingStatus.COMPLETED) }
    }

    @Test
    fun `updateStatusWithError delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.updateStatusWithError(id, RecordingStatus.FAILED, "Error")
        coVerify(exactly = 1) { recordingDao.updateStatusWithError(id, RecordingStatus.FAILED, "Error") }
    }

    @Test
    fun `updateTitle delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.updateTitle(id, "New Title")
        coVerify(exactly = 1) { recordingDao.updateTitle(id, "New Title") }
    }

    @Test
    fun `updateDuration delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.updateDuration(id, 2000L)
        coVerify(exactly = 1) { recordingDao.updateDuration(id, 2000L) }
    }

    @Test
    fun `updateExportInfo delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.updateExportInfo(id, "/new/path")
        coVerify(exactly = 1) { recordingDao.updateExportInfo(id, "/new/path", any()) }
    }

    @Test
    fun `delete delegates to dao`() = runTest {
        val recording = Recording(id = UUID.randomUUID(), title = "Test", audioPath = "", source = RecordingSource.APP)
        repository.delete(recording)
        coVerify(exactly = 1) { recordingDao.delete(recording) }
    }

    @Test
    fun `deleteById delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.deleteById(id)
        coVerify(exactly = 1) { recordingDao.deleteById(id) }
    }

    @Test
    fun `getTranscript delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        val expected = Transcript(id = UUID.randomUUID(), recordingId = id, rawText = "text")
        coEvery { transcriptDao.getTranscript(id) } returns expected
        val result = repository.getTranscript(id)
        assertEquals(expected, result)
    }

    @Test
    fun `getTranscriptFlow delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        val expected = Transcript(id = UUID.randomUUID(), recordingId = id, rawText = "text")
        coEvery { transcriptDao.getTranscriptFlow(id) } returns flowOf(expected)
        val result = repository.getTranscriptFlow(id).first()
        assertEquals(expected, result.value)
    }

    @Test
    fun `saveTranscript delegates to dao`() = runTest {
        val transcript = Transcript(id = UUID.randomUUID(), recordingId = UUID.randomUUID(), rawText = "text")
        coEvery { transcriptDao.getTranscript(transcript.recordingId) } returns null

        repository.saveTranscript(transcript)

        coVerify(exactly = 1) { transcriptDao.getTranscript(transcript.recordingId) }
        coVerify(exactly = 1) { transcriptDao.insert(transcript) }
    }

    @Test
    fun `updateRawText delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.updateRawText(id, "raw")
        coVerify(exactly = 1) { transcriptDao.updateRawText(id, "raw", any()) }


    }

    @Test
    fun `updateProcessedText delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.updateProcessedText(id, "processed", "mode")
        coVerify(exactly = 1) { transcriptDao.updateProcessedText(id, "processed", "mode", any()) }


    }

    @Test
    fun `updateSummary delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.updateSummary(id, "summary")
        coVerify(exactly = 1) { transcriptDao.updateSummary(id, "summary", any()) }
    }

    @Test
    fun `saveManualCorrection delegates to dao`() = runTest {
        val id = UUID.randomUUID()

        repository.saveManualCorrection(id, "corrected", "source")

        coVerify(exactly = 1) {
            transcriptDao.updateManualCorrection(
                recordingId = id,
                manualCorrectionText = "corrected",
                manualCorrectionSourceText = "source",
                updatedAt = any(),
            )
        }
    }

    @Test
    fun `clearManualCorrection delegates to dao`() = runTest {
        val id = UUID.randomUUID()

        repository.clearManualCorrection(id)

        coVerify(exactly = 1) {
            transcriptDao.updateManualCorrection(
                recordingId = id,
                manualCorrectionText = null,
                manualCorrectionSourceText = null,
                updatedAt = any(),
            )
        }
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

    @Test
    fun `deleteAll delegates to dao`() = runTest {
        repository.deleteAll()
        coVerify(exactly = 1) { recordingDao.deleteAll() }
    }
}
