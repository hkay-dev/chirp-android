package dev.chirpboard.app.data.repository

import androidx.room.withTransaction
import dev.chirpboard.app.data.dao.ProfileDao
import dev.chirpboard.app.data.dao.RecordingEnhancementSnapshotDao
import dev.chirpboard.app.data.dao.RecordingDao
import dev.chirpboard.app.data.dao.StructuredOutcomeSnapshotDao
import dev.chirpboard.app.data.dao.TranscriptDao
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.toEntity
import dev.chirpboard.app.data.entity.toModel
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.StructuredOutcomeGenerationStatus
import dev.chirpboard.app.data.model.StructuredOutcomeSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RecordingRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var recordingDao: RecordingDao
    private lateinit var transcriptDao: TranscriptDao
    private lateinit var structuredOutcomeSnapshotDao: StructuredOutcomeSnapshotDao
    private lateinit var enhancementSnapshotDao: RecordingEnhancementSnapshotDao
    private lateinit var repository: RecordingRepository

    @Before
    fun setup() {
        database = mockk(relaxed = true)
        recordingDao = mockk(relaxed = true)
        transcriptDao = mockk(relaxed = true)
        structuredOutcomeSnapshotDao = mockk(relaxed = true)
        enhancementSnapshotDao = mockk(relaxed = true)
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }
        repository =
            RecordingRepository(
                database,
                recordingDao,
                transcriptDao,
                structuredOutcomeSnapshotDao,
                enhancementSnapshotDao,
            )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun recording(
        status: RecordingStatus,
        executionToken: String? = null,
        errorMessage: String? = null,
    ): Recording =
        Recording(
            title = "Recording",
            audioPath = "/tmp/recording.m4a",
            source = RecordingSource.APP,
            status = status,
            errorMessage = errorMessage,
            transcriptionExecutionToken = executionToken,
        )

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
    fun `getTranscripts chunks large recording ID lists`() =
        runTest {
            val ids = List(1_005) { index ->
                UUID.nameUUIDFromBytes("recording-$index".toByteArray())
            }
            coEvery { transcriptDao.getTranscripts(any()) } answers {
                @Suppress("UNCHECKED_CAST")
                val batch = invocation.args[0] as List<UUID>
                batch.map { recordingId ->
                    Transcript(recordingId = recordingId, rawText = recordingId.toString())
                }
            }

            val transcripts = repository.getTranscripts(ids)

            assertEquals(ids.size, transcripts.size)
            coVerify(exactly = 2) { transcriptDao.getTranscripts(any()) }
        }

    @Test
    fun `claimTranscriptionExecution returns true and guards out terminal statuses`() =
        runTest {
            val id = UUID.randomUUID()
            val allowedStatuses = slot<List<RecordingStatus>>()
            coEvery {
                recordingDao.updateStatusWithTranscriptionToken(
                    id = id,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = null,
                    executionToken = "token-1",
                    allowedCurrentStatuses = capture(allowedStatuses),
                    expectedExecutionToken = null,
                )
            } returns 1

            val claimed = repository.claimTranscriptionExecution(id, "token-1")

            assertTrue(claimed)
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.PENDING_TRANSCRIPTION))
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.TRANSCRIBING))
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.ENHANCING))
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.FAILED))
            assertFalse(allowedStatuses.captured.contains(RecordingStatus.COMPLETED))
            assertFalse(allowedStatuses.captured.contains(RecordingStatus.RECORDING))
        }

    @Test
    fun `claimRetranscriptionExecution allows completed rows but still guards out recording`() =
        runTest {
            val id = UUID.randomUUID()
            val allowedStatuses = slot<List<RecordingStatus>>()
            coEvery {
                recordingDao.updateStatusWithTranscriptionToken(
                    id = id,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = null,
                    executionToken = "token-1",
                    allowedCurrentStatuses = capture(allowedStatuses),
                    expectedExecutionToken = null,
                )
            } returns 1

            val claimed = repository.claimRetranscriptionExecution(id, "token-1")

            assertTrue(claimed)
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.COMPLETED))
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.FAILED))
            assertFalse(allowedStatuses.captured.contains(RecordingStatus.RECORDING))
        }

    @Test
    fun `claimRetranscriptionExecution returns false when row is not claimable`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery {
                recordingDao.updateStatusWithTranscriptionToken(
                    id = any(),
                    status = any(),
                    errorMessage = any(),
                    executionToken = any(),
                    allowedCurrentStatuses = any(),
                    expectedExecutionToken = any(),
                )
            } returns 0

            assertFalse(repository.claimRetranscriptionExecution(id, "token-1"))
        }

    @Test
    fun `claimTranscriptionExecution returns false when row is not claimable`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery {
                recordingDao.updateStatusWithTranscriptionToken(
                    id = any(),
                    status = any(),
                    errorMessage = any(),
                    executionToken = any(),
                    allowedCurrentStatuses = any(),
                    expectedExecutionToken = any(),
                )
            } returns 0

            assertFalse(repository.claimTranscriptionExecution(id, "token-1"))
        }

    @Test
    fun `beginTranscriptionExecution resumes own interrupted transcribing run`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.getRecording(id) } returns
                recording(
                    status = RecordingStatus.TRANSCRIBING,
                    executionToken = "token-1",
                    errorMessage = "interrupted",
                )

            val resumed = repository.beginTranscriptionExecution(id, "token-1")

            assertEquals(RecordingStatus.TRANSCRIBING, resumed?.status)
            assertNull(resumed?.errorMessage)
            coVerify(exactly = 0) {
                recordingDao.updateStatusForTranscriptionExecution(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `beginTranscriptionExecution rejects transcribing row owned by another token`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.getRecording(id) } returns
                recording(status = RecordingStatus.TRANSCRIBING, executionToken = "other-token")

            assertNull(repository.beginTranscriptionExecution(id, "token-1"))
            coVerify(exactly = 0) {
                recordingDao.updateStatusForTranscriptionExecution(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `beginTranscriptionExecution promotes pending row to transcribing`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.getRecording(id) } returns
                recording(status = RecordingStatus.PENDING_TRANSCRIPTION, executionToken = "token-1")
            coEvery {
                recordingDao.updateStatusForTranscriptionExecution(
                    id = id,
                    expectedStatus = RecordingStatus.PENDING_TRANSCRIPTION,
                    executionToken = "token-1",
                    newStatus = RecordingStatus.TRANSCRIBING,
                    errorMessage = null,
                )
            } returns 1

            val started = repository.beginTranscriptionExecution(id, "token-1")

            assertEquals(RecordingStatus.TRANSCRIBING, started?.status)
        }

    @Test
    fun `updateStatusWithError allows already-pending transcription row`() =
        runTest {
            val id = UUID.randomUUID()
            val allowedStatuses = slot<List<RecordingStatus>>()
            coEvery {
                recordingDao.updateStatusWithErrorIfCurrentIn(
                    id = id,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = "recovery marker",
                    allowedStatuses = capture(allowedStatuses),
                )
            } returns 1

            val result =
                repository.updateStatusWithError(id, RecordingStatus.PENDING_TRANSCRIPTION, "recovery marker")

            assertEquals(RecordingStatusTransitionResult.TransitionApplied, result)
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.PENDING_TRANSCRIPTION))
        }

    @Test
    fun `updateStatusWithError allows already-pending enhancement row`() =
        runTest {
            val id = UUID.randomUUID()
            val allowedStatuses = slot<List<RecordingStatus>>()
            coEvery {
                recordingDao.updateStatusWithErrorIfCurrentIn(
                    id = id,
                    status = RecordingStatus.PENDING_ENHANCEMENT,
                    errorMessage = null,
                    allowedStatuses = capture(allowedStatuses),
                )
            } returns 1

            val result = repository.updateStatusWithError(id, RecordingStatus.PENDING_ENHANCEMENT, null)

            assertEquals(RecordingStatusTransitionResult.TransitionApplied, result)
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.PENDING_ENHANCEMENT))
        }

    @Test
    fun `token-guarded commitTranscriptionResult requires transcribing status and token in DAO update`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.getRecording(id) } returns
                recording(status = RecordingStatus.TRANSCRIBING, executionToken = "token-1")
            coEvery { transcriptDao.getTranscript(id) } returns null

            val committed =
                repository.commitTranscriptionResult(
                    transcript = Transcript(recordingId = id, rawText = "hello"),
                    timings = emptyList(),
                    enhancementIntent = null,
                    expectedExecutionToken = "token-1",
                    enhancementExecutionToken = null,
                )

            assertTrue(committed)
            coVerify(exactly = 1) {
                recordingDao.updateStatusWithTranscriptionToken(
                    id = id,
                    status = RecordingStatus.COMPLETED,
                    errorMessage = null,
                    executionToken = null,
                    allowedCurrentStatuses = listOf(RecordingStatus.TRANSCRIBING),
                    expectedExecutionToken = "token-1",
                )
            }
        }

    @Test
    fun `token-guarded commitTranscriptionResult rejects stale execution token`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.getRecording(id) } returns
                recording(status = RecordingStatus.TRANSCRIBING, executionToken = "newer-token")

            val committed =
                repository.commitTranscriptionResult(
                    transcript = Transcript(recordingId = id, rawText = "hello"),
                    timings = emptyList(),
                    enhancementIntent = null,
                    expectedExecutionToken = "token-1",
                    enhancementExecutionToken = null,
                )

            assertFalse(committed)
            coVerify(exactly = 0) {
                recordingDao.updateStatusWithTranscriptionToken(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `isAutoTranscribeEnabled defaults to true without recording or profile association`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.getRecording(id) } returns null
            assertTrue(repository.isAutoTranscribeEnabled(id))

            coEvery { recordingDao.getRecording(id) } returns recording(status = RecordingStatus.PENDING_TRANSCRIPTION)
            assertTrue(repository.isAutoTranscribeEnabled(id))
        }

    @Test
    fun `isAutoTranscribeEnabled honors the profile opt-out`() =
        runTest {
            val id = UUID.randomUUID()
            val profileId = UUID.randomUUID()
            val profileDao = mockk<ProfileDao>()
            every { database.profileDao() } returns profileDao
            coEvery { recordingDao.getRecording(id) } returns
                recording(status = RecordingStatus.PENDING_TRANSCRIPTION).copy(profileId = profileId)

            coEvery { profileDao.getProfile(profileId) } returns
                Profile(id = profileId, name = "Quiet", autoTranscribe = false)
            assertFalse(repository.isAutoTranscribeEnabled(id))

            coEvery { profileDao.getProfile(profileId) } returns
                Profile(id = profileId, name = "Normal", autoTranscribe = true)
            assertTrue(repository.isAutoTranscribeEnabled(id))

            // Deleted profile: never strand the recording behind a missing opt-out.
            coEvery { profileDao.getProfile(profileId) } returns null
            assertTrue(repository.isAutoTranscribeEnabled(id))
        }

    @Test
    fun `markAwaitingManualTranscription clears the execution token from queue states only`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery {
                recordingDao.updateStatusWithTranscriptionToken(any(), any(), any(), any(), any(), any())
            } returns 1

            assertTrue(repository.markAwaitingManualTranscription(id))

            coVerify(exactly = 1) {
                recordingDao.updateStatusWithTranscriptionToken(
                    id = id,
                    status = RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION,
                    errorMessage = null,
                    executionToken = null,
                    allowedCurrentStatuses =
                        listOf(RecordingStatus.PENDING_TRANSCRIPTION, RecordingStatus.TRANSCRIBING),
                    expectedExecutionToken = null,
                )
            }
        }

    @Test
    fun `resolveCancelledEnhancement keeps committed transcript and completes the row`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.getStatus(id) } returns RecordingStatus.ENHANCING
            coEvery { transcriptDao.getTranscript(id) } returns Transcript(recordingId = id, rawText = "hello")
            coEvery {
                recordingDao.updateStatusWithTranscriptionToken(any(), any(), any(), any(), any(), any())
            } returns 1

            assertTrue(repository.resolveCancelledEnhancement(id))

            coVerify(exactly = 1) { enhancementSnapshotDao.deleteByRecordingId(id) }
            coVerify(exactly = 1) {
                recordingDao.updateStatusWithTranscriptionToken(
                    id = id,
                    status = RecordingStatus.COMPLETED,
                    errorMessage = null,
                    executionToken = null,
                    allowedCurrentStatuses = listOf(RecordingStatus.ENHANCING),
                    expectedExecutionToken = null,
                )
            }
        }

    @Test
    fun `resolveCancelledEnhancement refuses rows outside enhancement states`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.getStatus(id) } returns RecordingStatus.COMPLETED

            assertFalse(repository.resolveCancelledEnhancement(id))

            coVerify(exactly = 0) { enhancementSnapshotDao.deleteByRecordingId(any()) }
        }
}
