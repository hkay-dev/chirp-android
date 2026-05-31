package dev.chirpboard.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.model.RecordingEnhancementIntent
import dev.chirpboard.app.data.model.RecordingEnhancementResult
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                enhancementSnapshotDao = database.recordingEnhancementSnapshotDao(),
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
    fun parentRecordingUpdate_preservesTranscriptTimingsAndTags() =
        runBlocking {
            val recording =
                Recording(
                    title = "Parent update",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                )
            val tag = Tag(name = "Keep")
            database.tagDao().insert(tag)
            repository.createRecordingWithTranscript(
                recording = recording,
                transcript = Transcript(recordingId = recording.id, rawText = "keep transcript"),
                timings = listOf(TranscriptTiming(recording.id, 0, "keep", 0L, 100L)),
            )
            database.tagDao().addTagToRecording(dev.chirpboard.app.data.entity.RecordingTag(recording.id, tag.id))

            repository.update(recording.copy(title = "Updated title", status = RecordingStatus.TRANSCRIBING))

            assertEquals("keep transcript", repository.getTranscript(recording.id)?.rawText)
            assertEquals(1, repository.getTranscriptTimings(recording.id).size)
            assertEquals(listOf(tag.id), database.tagDao().getTagsForRecordingList(recording.id).map { it.id })
        }

    @Test
    fun createInProgressRecording_appliesProfileDefaultTagsAtomically() =
        runBlocking {
            val alpha = Tag(name = "Alpha")
            val beta = Tag(name = "Beta")
            database.tagDao().insert(alpha)
            database.tagDao().insert(beta)
            val profile = Profile(name = "Defaults")
            database.profileDao().insertWithDefaultTags(profile, listOf(alpha.id, beta.id))

            val recording =
                repository.createInProgressRecording(
                    title = "Live defaults",
                    audioPath = "",
                    source = RecordingSource.APP,
                    profileId = profile.id,
                )

            val assignedIds = database.tagDao().getTagsForRecordingList(recording.id).map { it.id }.toSet()
            assertEquals(setOf(alpha.id, beta.id), assignedIds)
        }

    @Test
    fun profileMetadataUpdate_preservesDefaultTagRelationships() =
        runBlocking {
            val tag = Tag(name = "Default")
            database.tagDao().insert(tag)
            val profile = Profile(name = "Before")
            database.profileDao().insertWithDefaultTags(profile, listOf(tag.id))

            database.profileDao().update(profile.copy(name = "After"))

            assertEquals(listOf(tag.id), database.profileDao().getDefaultTagIds(profile.id))
        }

    @Test
    fun tagMetadataUpdate_preservesRecordingAndProfileRelationships() =
        runBlocking {
            val tag = Tag(name = "Before", color = "#111111")
            val profile = Profile(name = "Profile default")
            val recording =
                Recording(
                    title = "Tagged",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                )
            database.tagDao().insert(tag)
            database.profileDao().insertWithDefaultTags(profile, listOf(tag.id))
            repository.insert(recording)
            database.tagDao().addTagToRecording(dev.chirpboard.app.data.entity.RecordingTag(recording.id, tag.id))

            database.tagDao().update(tag.copy(name = "After", color = "#222222"))

            assertEquals(listOf(tag.id), database.profileDao().getDefaultTagIds(profile.id))
            assertEquals(listOf(tag.id), database.tagDao().getTagsForRecordingList(recording.id).map { it.id })
        }

    @Test
    fun setTagsForRecording_invalidTagLeavesExistingAssignments() =
        runBlocking {
            val recording =
                Recording(
                    title = "Invalid tag",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                )
            val existing = Tag(name = "Existing")
            val replacement = Tag(name = "Replacement")
            database.tagDao().insert(existing)
            database.tagDao().insert(replacement)
            repository.insert(recording)
            database.tagDao().setTagsForRecording(recording.id, listOf(existing.id))

            var threw = false
            try {
                database.tagDao().setTagsForRecording(recording.id, listOf(replacement.id, UUID.randomUUID()))
            } catch (e: IllegalArgumentException) {
                threw = true
            }

            assertTrue(threw)
            assertEquals(listOf(existing.id), database.tagDao().getTagsForRecordingList(recording.id).map { it.id })
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

    @Test
    fun finalizeInProgressRecording_rejectsAlreadyCompletedRecording() =
        runBlocking {
            val recording =
                Recording(
                    title = "Already saved",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.COMPLETED,
                    durationMs = 100L,
                )
            repository.insert(recording)

            val result =
                repository.finalizeInProgressRecording(
                    recordingId = recording.id,
                    durationMs = 200L,
                    title = "Stale finalize",
                )

            val persisted = repository.getRecording(recording.id)
            assertEquals(recording.id, result?.id)
            assertEquals("Already saved", persisted?.title)
            assertEquals(100L, persisted?.durationMs)
            assertEquals(RecordingStatus.COMPLETED, persisted?.status)
        }

    @Test
    fun staleStatusTransition_doesNotOverwriteTerminalState() =
        runBlocking {
            val recording =
                Recording(
                    title = "Terminal",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.COMPLETED,
                )
            repository.insert(recording)

            val result =
                repository.transitionRecordingStatus(
                    id = recording.id,
                    destinationStatus = RecordingStatus.FAILED,
                    allowedSourceStatuses = listOf(RecordingStatus.TRANSCRIBING),
                    errorMessage = "stale worker",
                )

            assertEquals(
                RecordingStatusTransitionResult.AlreadyTerminal(RecordingStatus.COMPLETED),
                result,
            )
            assertEquals(RecordingStatus.COMPLETED, repository.getRecording(recording.id)?.status)
            assertNull(repository.getRecording(recording.id)?.errorMessage)
        }

    @Test
    fun staleTranscriptionCommit_doesNotOverwriteCompletedTranscript() =
        runBlocking {
            val recording =
                Recording(
                    title = "Completed",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.COMPLETED,
                )
            repository.createRecordingWithTranscript(
                recording = recording,
                transcript = Transcript(recordingId = recording.id, rawText = "existing raw"),
            )

            val result =
                repository.commitTranscriptionResult(
                    transcript = Transcript(recordingId = recording.id, rawText = "stale raw"),
                    timings = emptyList(),
                    enhancementIntent = null,
                )

            assertEquals(
                RecordingStatusTransitionResult.AlreadyTerminal(RecordingStatus.COMPLETED),
                result,
            )
            assertEquals("existing raw", repository.getTranscript(recording.id)?.rawText)
            assertEquals(RecordingStatus.COMPLETED, repository.getRecording(recording.id)?.status)
        }

    @Test
    fun guardedTranscriptionCommit_rejectsMismatchedExecutionToken() =
        runBlocking {
            val recording =
                Recording(
                    title = "Owned transcription",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.TRANSCRIBING,
                    transcriptionExecutionToken = "current-token",
                )
            repository.createRecordingWithTranscript(
                recording = recording,
                transcript = Transcript(recordingId = recording.id, rawText = "existing raw"),
            )

            val committed =
                repository.commitTranscriptionResult(
                    transcript = Transcript(recordingId = recording.id, rawText = "stale raw"),
                    timings = emptyList(),
                    enhancementIntent = null,
                    expectedExecutionToken = "stale-token",
                    enhancementExecutionToken = null,
                )

            assertFalse(committed)
            assertEquals("existing raw", repository.getTranscript(recording.id)?.rawText)
            assertEquals(RecordingStatus.TRANSCRIBING, repository.getRecording(recording.id)?.status)
        }

    @Test
    fun commitTranscriptionResult_persistsIntentAndPendingStatusAtomically() =
        runBlocking {
            val recording =
                Recording(
                    title = "Enhance after transcript",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.TRANSCRIBING,
                )
            repository.insert(recording)

            repository.commitTranscriptionResult(
                transcript =
                    Transcript(
                        recordingId = recording.id,
                        rawText = "raw transcript",
                        processedText = "processed transcript",
                        processingMode = "word_replacement",
                    ),
                timings = emptyList(),
                enhancementIntent =
                    RecordingEnhancementIntent(
                        processingModeId = "cleanup",
                        autoTitle = true,
                        autoSummary = false,
                    ),
            )

            val persistedRecording = repository.getRecording(recording.id)
            val snapshot = repository.beginEnhancement(recording.id)

            assertEquals(RecordingStatus.PENDING_ENHANCEMENT, persistedRecording?.status)
            assertNotNull(snapshot)
            assertEquals("cleanup", snapshot?.intent?.processingModeId)
            assertEquals(true, snapshot?.intent?.autoTitle)
        }

    @Test
    fun completeEnhancement_updatesOutputsDeletesIntentAndCompletes() =
        runBlocking {
            val recording =
                Recording(
                    title = "Enhance complete",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.TRANSCRIBING,
                )
            repository.insert(recording)
            repository.commitTranscriptionResult(
                transcript = Transcript(recordingId = recording.id, rawText = "raw transcript"),
                timings = emptyList(),
                enhancementIntent =
                    RecordingEnhancementIntent(
                        processingModeId = null,
                        autoTitle = true,
                        autoSummary = true,
                    ),
            )

            val snapshot = repository.beginEnhancement(recording.id)
            assertNotNull(snapshot)

            repository.completeEnhancement(
                recording.id,
                RecordingEnhancementResult(
                    processedText = "processed transcript",
                    processingMode = "cleanup",
                    title = "Generated title",
                    summary = "Generated summary",
                ),
            )

            val persistedRecording = repository.getRecording(recording.id)
            val persistedTranscript = repository.getTranscript(recording.id)

            assertEquals(RecordingStatus.COMPLETED, persistedRecording?.status)
            assertEquals("Generated title", persistedRecording?.title)
            assertEquals("processed transcript", persistedTranscript?.processedText)
            assertEquals("cleanup", persistedTranscript?.processingMode)
            assertEquals("Generated summary", persistedTranscript?.summary)
            assertNull(repository.beginEnhancement(recording.id))
        }

    @Test
    fun guardedEnhancementCommit_rejectsMismatchedExecutionTokenAndSourceRevision() =
        runBlocking {
            val recording =
                Recording(
                    title = "Enhance guard",
                    audioPath = "",
                    source = RecordingSource.APP,
                    status = RecordingStatus.TRANSCRIBING,
                    transcriptionExecutionToken = "transcription-token",
                )
            repository.insert(recording)
            repository.commitTranscriptionResult(
                transcript = Transcript(recordingId = recording.id, rawText = "raw transcript"),
                timings = emptyList(),
                enhancementIntent =
                    RecordingEnhancementIntent(
                        processingModeId = null,
                        autoTitle = true,
                        autoSummary = false,
                    ),
                expectedExecutionToken = "transcription-token",
                enhancementExecutionToken = "current-enhancement-token",
            )
            assertNotNull(repository.beginEnhancement(recording.id, "current-enhancement-token"))

            val staleTokenCommitted =
                repository.completeEnhancement(
                    recordingId = recording.id,
                    executionToken = "stale-enhancement-token",
                    sourceTranscriptRevision = "raw transcript||",
                    result =
                        RecordingEnhancementResult(
                            processedText = null,
                            processingMode = null,
                            title = "Stale title",
                            summary = null,
                        ),
                )
            val staleRevisionCommitted =
                repository.completeEnhancement(
                    recordingId = recording.id,
                    executionToken = "current-enhancement-token",
                    sourceTranscriptRevision = "changed transcript||",
                    result =
                        RecordingEnhancementResult(
                            processedText = null,
                            processingMode = null,
                            title = "Wrong revision title",
                            summary = null,
                        ),
                )

            assertFalse(staleTokenCommitted)
            assertFalse(staleRevisionCommitted)
            assertEquals("Enhance guard", repository.getRecording(recording.id)?.title)
            assertEquals(RecordingStatus.ENHANCING, repository.getRecording(recording.id)?.status)
            assertTrue(repository.hasUnresolvedEnhancementSnapshot(recording.id))
        }
}
