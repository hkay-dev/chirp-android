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
import dev.chirpboard.app.data.entity.RecordingEnhancementSnapshotEntity
import dev.chirpboard.app.data.entity.toEntity
import dev.chirpboard.app.data.entity.toModel
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.EnhancementSubworkStatus
import dev.chirpboard.app.data.model.RecordingEnhancementResult
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.StructuredOutcomeGenerationStatus
import dev.chirpboard.app.data.model.StructuredOutcomeSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
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

    private fun enhancementSnapshot(recordingId: UUID): RecordingEnhancementSnapshotEntity =
        RecordingEnhancementSnapshotEntity(
            recordingId = recordingId,
            sourceTranscriptRevision = "raw transcript||",
            sourceProcessedTextRevision = null,
            processingModeRequested = false,
            processingModeId = null,
            processingModeLabel = null,
            processingModeType = null,
            processingModePrompt = null,
            processingModeStatus = EnhancementSubworkStatus.SKIPPED,
            processingModeErrorMessage = null,
            titleRequested = true,
            titleStatus = EnhancementSubworkStatus.PENDING,
            titleErrorMessage = null,
            summaryRequested = false,
            summaryStatus = EnhancementSubworkStatus.SKIPPED,
            summaryErrorMessage = null,
            llmProviderId = "vertex",
            llmModelId = null,
            activeEnhancementExecutionToken = null,
            legacyRequiresResolution = false,
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
                recordingDao.claimTranscriptionExecution(
                    id = id,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = null,
                    executionToken = "token-1",
                    allowedCurrentStatuses = capture(allowedStatuses),
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
                recordingDao.claimTranscriptionExecution(
                    id = id,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = null,
                    executionToken = "token-1",
                    allowedCurrentStatuses = capture(allowedStatuses),
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
                recordingDao.claimTranscriptionExecution(
                    id = any(),
                    status = any(),
                    errorMessage = any(),
                    executionToken = any(),
                    allowedCurrentStatuses = any(),
                )
            } returns 0

            assertFalse(repository.claimRetranscriptionExecution(id, "token-1"))
        }

    @Test
    fun `claimTranscriptionExecution returns false when row is not claimable`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery {
                recordingDao.claimTranscriptionExecution(
                    id = any(),
                    status = any(),
                    errorMessage = any(),
                    executionToken = any(),
                    allowedCurrentStatuses = any(),
                )
            } returns 0

            assertFalse(repository.claimTranscriptionExecution(id, "token-1"))
        }

    @Test
    fun `claimEnhancementExecution re-arms terminal delivery in the claim transaction`() =
        runTest {
            val id = UUID.randomUUID()
            val snapshot = enhancementSnapshot(id)
            val allowedStatuses = slot<List<RecordingStatus>>()
            coEvery { enhancementSnapshotDao.getSnapshot(id) } returns snapshot
            coEvery {
                recordingDao.updateStatusWithErrorIfCurrentIn(
                    id = id,
                    status = RecordingStatus.PENDING_ENHANCEMENT,
                    errorMessage = null,
                    allowedStatuses = capture(allowedStatuses),
                )
            } returns 1
            coEvery { recordingDao.rearmTerminalNotification(id) } returns 1

            val claimed = repository.claimEnhancementExecution(id, "enhancement-token")

            assertTrue(claimed)
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.PENDING_ENHANCEMENT))
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.ENHANCING))
            assertTrue(allowedStatuses.captured.contains(RecordingStatus.FAILED))
            coVerifyOrder {
                enhancementSnapshotDao.getSnapshot(id)
                recordingDao.updateStatusWithErrorIfCurrentIn(
                    id = id,
                    status = RecordingStatus.PENDING_ENHANCEMENT,
                    errorMessage = null,
                    allowedStatuses = any(),
                )
                enhancementSnapshotDao.upsert(
                    match {
                        it.recordingId == id &&
                            it.activeEnhancementExecutionToken == "enhancement-token" &&
                            it.lastErrorMessage == null
                    },
                )
                recordingDao.rearmTerminalNotification(id)
            }
        }

    @Test
    fun `reparkEnhancementExecution keeps the active token and pending subwork`() =
        runTest {
            val id = UUID.randomUUID()
            val snapshot =
                enhancementSnapshot(id).copy(
                    activeEnhancementExecutionToken = "enhancement-token",
                )
            coEvery { enhancementSnapshotDao.getSnapshot(id) } returns snapshot
            coEvery {
                recordingDao.updateStatusWithErrorIfCurrentIn(
                    id = id,
                    status = RecordingStatus.PENDING_ENHANCEMENT,
                    errorMessage = "temporary outage",
                    allowedStatuses = listOf(RecordingStatus.ENHANCING),
                )
            } returns 1

            val reparked =
                repository.reparkEnhancementExecution(
                    recordingId = id,
                    executionToken = "enhancement-token",
                    errorMessage = "temporary outage",
                )

            assertTrue(reparked)
            coVerifyOrder {
                enhancementSnapshotDao.getSnapshot(id)
                recordingDao.updateStatusWithErrorIfCurrentIn(
                    id = id,
                    status = RecordingStatus.PENDING_ENHANCEMENT,
                    errorMessage = "temporary outage",
                    allowedStatuses = listOf(RecordingStatus.ENHANCING),
                )
                enhancementSnapshotDao.upsert(
                    match {
                        it.recordingId == id &&
                            it.activeEnhancementExecutionToken == "enhancement-token" &&
                            it.titleStatus == EnhancementSubworkStatus.PENDING &&
                            it.lastErrorMessage == "temporary outage"
                    },
                )
            }
        }

    @Test
    fun `reparkEnhancementExecution rejects a stale token`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { enhancementSnapshotDao.getSnapshot(id) } returns
                enhancementSnapshot(id).copy(activeEnhancementExecutionToken = "new-owner")

            val reparked =
                repository.reparkEnhancementExecution(
                    recordingId = id,
                    executionToken = "stale-owner",
                    errorMessage = "temporary outage",
                )

            assertFalse(reparked)
            coVerify(exactly = 0) { recordingDao.updateStatusWithErrorIfCurrentIn(any(), any(), any(), any()) }
            coVerify(exactly = 0) { enhancementSnapshotDao.upsert(any()) }
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
    fun `audio path swap carries the source path and execution token guards`() =
        runTest {
            val id = UUID.randomUUID()
            var updatedRows = 1
            coEvery {
                recordingDao.swapAudioPathForTranscriptionExecution(
                    id = id,
                    executionToken = "token-1",
                    expectedAudioPath = "/tmp/capture.f32pcm",
                    newAudioPath = "/tmp/capture.wav",
                )
            } answers { updatedRows }

            assertTrue(
                repository.swapAudioPathForTranscriptionExecution(
                    recordingId = id,
                    executionToken = "token-1",
                    expectedAudioPath = "/tmp/capture.f32pcm",
                    newAudioPath = "/tmp/capture.wav",
                ),
            )
            updatedRows = 0
            assertFalse(
                repository.swapAudioPathForTranscriptionExecution(
                    recordingId = id,
                    executionToken = "token-1",
                    expectedAudioPath = "/tmp/capture.f32pcm",
                    newAudioPath = "/tmp/capture.wav",
                ),
            )
            coVerify(exactly = 2) {
                recordingDao.swapAudioPathForTranscriptionExecution(
                    id = id,
                    executionToken = "token-1",
                    expectedAudioPath = "/tmp/capture.f32pcm",
                    newAudioPath = "/tmp/capture.wav",
                )
            }
        }

    @Test
    fun `engine reroute carries the execution token and expected engine guards`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery {
                recordingDao.rerouteTranscriptionEngineForExecution(
                    id = id,
                    executionToken = "token-1",
                    expectedEngineId = "google_cloud_chirp_3",
                    newEngineId = "local_parakeet",
                )
            } returns 1

            val rerouted =
                repository.rerouteTranscriptionEngineForExecution(
                    recordingId = id,
                    executionToken = "token-1",
                    expectedEngineId = "google_cloud_chirp_3",
                    newEngineId = "local_parakeet",
                )

            assertTrue(rerouted)
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

    @Test
    fun `getPendingRecordings loads only queue states so awaiting-manual rows are never auto-recovered`() =
        runTest {
            // Recover-stuck/startup recovery feed exclusively from this query: parking a
            // recording in AWAITING_MANUAL_TRANSCRIPTION (profile opt-out / user cancel)
            // only sticks if automatic recovery can never see it here.
            val expected = listOf(recording(status = RecordingStatus.PENDING_TRANSCRIPTION))
            val statuses = slot<List<RecordingStatus>>()
            coEvery { recordingDao.getRecordingsByStatuses(capture(statuses)) } returns expected

            val result = repository.getPendingRecordings()

            assertEquals(expected, result)
            assertEquals(
                listOf(RecordingStatus.PENDING_TRANSCRIPTION, RecordingStatus.PENDING_ENHANCEMENT),
                statuses.captured,
            )
            assertFalse(statuses.captured.contains(RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION))
        }

    @Test
    fun `updateExportInfo stamps the export path and a current export time on the row`() =
        runTest {
            // Export bookkeeping: the studio surfaces "exported to ..." from these two
            // columns, so a successful Obsidian export must record both.
            val id = UUID.randomUUID()
            val exportedAt = slot<java.util.Date>()
            coEvery { recordingDao.updateExportInfo(id, "vault/Note.md", capture(exportedAt)) } returns Unit

            val before = System.currentTimeMillis()
            repository.updateExportInfo(id, "vault/Note.md")
            val after = System.currentTimeMillis()

            coVerify(exactly = 1) { recordingDao.updateExportInfo(id, "vault/Note.md", any()) }
            assertTrue(exportedAt.captured.time in before..after)
        }

    @Test
    fun `updateNotes stores non-blank text verbatim and reports the row update`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.updateNotes(id, "Standup riff\nabout Q3 roadmap") } returns 1

            assertTrue(repository.updateNotes(id, "Standup riff\nabout Q3 roadmap"))

            coVerify(exactly = 1) { recordingDao.updateNotes(id, "Standup riff\nabout Q3 roadmap") }
        }

    @Test
    fun `updateNotes normalizes blank text to null so has-a-note stays a null check`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.updateNotes(id, null) } returns 1

            assertTrue(repository.updateNotes(id, "   \n\t"))
            assertTrue(repository.updateNotes(id, null))

            coVerify(exactly = 2) { recordingDao.updateNotes(id, null) }
            coVerify(exactly = 0) { recordingDao.updateNotes(id, match { it != null }) }
        }

    @Test
    fun `updateNotes against a missing row is a no-op that reports false`() =
        runTest {
            // The record screen flushes the note draft after stop/discard races; a write that
            // lands after the row was deleted must stay harmless and observable as a no-op.
            val id = UUID.randomUUID()
            coEvery { recordingDao.updateNotes(id, "orphan note") } returns 0

            assertFalse(repository.updateNotes(id, "orphan note"))
        }

    @Test
    fun `getNotes returns the persisted note through the dao`() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { recordingDao.getNotes(id) } returns "Persisted note"

            assertEquals("Persisted note", repository.getNotes(id))

            coEvery { recordingDao.getNotes(id) } returns null
            assertNull(repository.getNotes(id))
        }

    @Test
    fun `completeEnhancement applies the generated title only while the title is unrenamed`() =
        runTest {
            val recordingId = UUID.randomUUID()
            coEvery { recordingDao.getStatus(recordingId) } returns RecordingStatus.ENHANCING
            coEvery { enhancementSnapshotDao.getSnapshot(recordingId) } returns
                enhancementSnapshot(recordingId).copy(activeEnhancementExecutionToken = "token-1")
            coEvery { transcriptDao.getTranscript(recordingId) } returns
                Transcript(recordingId = recordingId, rawText = "raw transcript")

            val committed =
                repository.completeEnhancement(
                    recordingId = recordingId,
                    executionToken = "token-1",
                    sourceTranscriptRevision = "raw transcript||",
                    sourceTitle = "Recording 3:41 PM",
                    result =
                        RecordingEnhancementResult(
                            processedText = null,
                            processingMode = null,
                            title = "Groceries and errands",
                            summary = null,
                            titleStatus = EnhancementSubworkStatus.SUCCEEDED,
                        ),
                )

            assertTrue(committed)
            // Conditional write: a rename made while ENHANCING wins over the generated title.
            coVerify(exactly = 1) {
                recordingDao.updateTitleIfCurrent(
                    recordingId,
                    "Groceries and errands",
                    expectedTitle = "Recording 3:41 PM",
                )
            }
            coVerify(exactly = 0) { recordingDao.updateTitle(any(), any()) }
        }

    @Test
    fun `searchRecordings escapes LIKE metacharacters so they match literally`() =
        runTest {
            repository.searchRecordings("""50%_\ off""")

            // \ -> \\, % -> \%, _ -> \_, wrapped in the contains-match wildcards.
            verify { recordingDao.searchRecordings("""%50\%\_\\ off%""", any()) }
        }
}
