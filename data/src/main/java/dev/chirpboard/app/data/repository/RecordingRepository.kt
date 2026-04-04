package dev.chirpboard.app.data.repository

import androidx.room.withTransaction
import dev.chirpboard.app.data.dao.RecordingDao
import dev.chirpboard.app.data.dao.TranscriptDao
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing recordings and their transcripts.
 *
 * Note on deletion: Transcript has @ForeignKey with onDelete = CASCADE,
 * so deleting a Recording automatically deletes its associated Transcript.
 * No explicit transaction needed for single recording deletes.
 */
@Singleton
class RecordingRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        private val recordingDao: RecordingDao,
        private val transcriptDao: TranscriptDao,
    ) {
        /** Get all recordings ordered by creation date (newest first) */
        fun getAllRecordings(): Flow<List<Recording>> = recordingDao.getAllRecordings()

        /** Get a single recording by ID */
        suspend fun getRecording(id: UUID): Recording? = recordingDao.getRecording(id)

        /** Get recording as Flow for reactive updates */
        fun getRecordingFlow(id: UUID): Flow<Recording?> = recordingDao.getRecordingFlow(id)

        /** Get recordings with a specific status */
        fun getRecordingsByStatus(status: RecordingStatus): Flow<List<Recording>> = recordingDao.getRecordingsByStatus(status)

        /** Get all recordings pending processing (for queue management) */
        suspend fun getPendingRecordings(): List<Recording> =
            recordingDao.getRecordingsByStatuses(
                listOf(RecordingStatus.PENDING_TRANSCRIPTION, RecordingStatus.PENDING_ENHANCEMENT),
            )

        /** Search recordings by title */
        fun searchRecordings(query: String): Flow<List<Recording>> = recordingDao.searchRecordings(query)

        /** Create a new recording */
        suspend fun createRecording(
            title: String,
            audioPath: String,
            source: RecordingSource,
            profileId: UUID? = null,
            durationMs: Long = 0,
        ): Recording {
            val recording =
                Recording(
                    title = title,
                    audioPath = audioPath,
                    source = source,
                    profileId = profileId,
                    durationMs = durationMs,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                )
            recordingDao.insert(recording)
            return recording
        }

        /** Insert an existing recording */
        suspend fun insert(recording: Recording) = recordingDao.insert(recording)

        /** Update a recording */
        suspend fun update(recording: Recording) = recordingDao.update(recording)

        /** Update recording status */
        suspend fun updateStatus(
            id: UUID,
            status: RecordingStatus,
        ) = recordingDao.updateStatus(id, status)

        /** Update recording status with error message */
        suspend fun updateStatusWithError(
            id: UUID,
            status: RecordingStatus,
            errorMessage: String?,
        ) = recordingDao.updateStatusWithError(id, status, errorMessage)

        /** Update recording title */
        suspend fun updateTitle(
            id: UUID,
            title: String,
        ) = recordingDao.updateTitle(id, title)

        /** Update recording duration */
        suspend fun updateDuration(
            id: UUID,
            durationMs: Long,
        ) = recordingDao.updateDuration(id, durationMs)

        /** Update export info */
        suspend fun updateExportInfo(
            id: UUID,
            path: String,
        ) = recordingDao.updateExportInfo(id, path, Date())

        /** Delete a recording */
        suspend fun delete(recording: Recording) = recordingDao.delete(recording)

        /** Delete a recording by ID */
        suspend fun deleteById(id: UUID) = recordingDao.deleteById(id)

        // Transcript operations

        /** Get transcript for a recording */
        suspend fun getTranscript(recordingId: UUID): Transcript? = transcriptDao.getTranscript(recordingId)

        /** Get transcript as Flow */
        fun getTranscriptFlow(recordingId: UUID): Flow<Transcript?> = transcriptDao.getTranscriptFlow(recordingId)

        /** Create or update transcript */
        suspend fun saveTranscript(transcript: Transcript) = transcriptDao.insert(transcript)

        /** Update raw text */
        suspend fun updateRawText(
            recordingId: UUID,
            rawText: String,
        ) = transcriptDao.updateRawText(recordingId, rawText)

        /** Update processed text */
        suspend fun updateProcessedText(
            recordingId: UUID,
            processedText: String,
            mode: String,
        ) = transcriptDao.updateProcessedText(recordingId, processedText, mode)

        /** Update summary */
        suspend fun updateSummary(
            recordingId: UUID,
            summary: String,
        ) = transcriptDao.updateSummary(recordingId, summary)

        /** Delete all recordings (for dev/testing) */
        suspend fun deleteAll() = recordingDao.deleteAll()

        // Transactional operations

        /**
         * Create a recording with its transcript atomically.
         * Both succeed or both fail - prevents orphaned records.
         */
        suspend fun createRecordingWithTranscript(
            recording: Recording,
            transcript: Transcript,
        ): Recording =
            database.withTransaction {
                recordingDao.insert(recording)
                transcriptDao.insert(transcript)
                recording
            }

        /**
         * Delete multiple recordings in a transaction.
         * Processes in batches of 100 to respect SQLite variable limits.
         *
         * Note: Associated transcripts are automatically deleted via CASCADE.
         */
        suspend fun deleteRecordings(ids: List<UUID>) {
            ids.chunked(100).forEach { batch ->
                database.withTransaction {
                    batch.forEach { id ->
                        recordingDao.deleteById(id)
                    }
                }
            }
        }
    }
