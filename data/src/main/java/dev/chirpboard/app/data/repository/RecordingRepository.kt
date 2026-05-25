package dev.chirpboard.app.data.repository

import androidx.room.withTransaction
import dev.chirpboard.app.data.dao.RecordingDao
import dev.chirpboard.app.data.dao.StructuredOutcomeSnapshotDao
import dev.chirpboard.app.data.dao.TranscriptDao
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.entity.toEntity
import dev.chirpboard.app.data.entity.toModel
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.StructuredOutcomeGenerationStatus
import dev.chirpboard.app.data.model.StructuredOutcomeSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing recordings and their transcripts.
 *
 * Note on deletion: Transcript and TranscriptTiming both cascade from Recording,
 * so deleting a Recording automatically deletes its associated transcript data.
 * No explicit transaction is needed for single recording deletes.
 */
@Singleton
class RecordingRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        private val recordingDao: RecordingDao,
        private val transcriptDao: TranscriptDao,
        private val structuredOutcomeSnapshotDao: StructuredOutcomeSnapshotDao,
    ) {
        companion object {
            private const val TAG = "RecordingRepository"
        }

        fun getAllRecordings(): Flow<RepositoryFlowState<List<Recording>>> =
            recordingDao.getAllRecordings().catchRepositoryFlowState(TAG, emptyList())
        suspend fun getAllAudioPaths(): List<String> = recordingDao.getAllAudioPaths()

        suspend fun getRecording(id: UUID): Recording? = recordingDao.getRecording(id)

        fun getRecordingFlow(id: UUID): Flow<RepositoryFlowState<Recording?>> =
            recordingDao.getRecordingFlow(id).catchRepositoryFlowState(TAG, null)

        fun getRecordingsByStatus(status: RecordingStatus): Flow<RepositoryFlowState<List<Recording>>> =
            recordingDao.getRecordingsByStatus(status).catchRepositoryFlowState(TAG, emptyList())

        suspend fun getPendingRecordings(): List<Recording> =
            recordingDao.getRecordingsByStatuses(
                listOf(RecordingStatus.PENDING_TRANSCRIPTION, RecordingStatus.PENDING_ENHANCEMENT),
            )

        fun searchRecordings(query: String): Flow<RepositoryFlowState<List<Recording>>> =
            recordingDao.searchRecordings(query).catchRepositoryFlowState(TAG, emptyList())

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

        suspend fun createInProgressRecording(
            title: String,
            audioPath: String,
            source: RecordingSource,
            profileId: UUID? = null,
        ): Recording {
            val recording =
                Recording(
                    title = title,
                    audioPath = audioPath,
                    source = source,
                    profileId = profileId,
                    durationMs = 0,
                    status = RecordingStatus.RECORDING,
                )
            recordingDao.insert(recording)
            return recording
        }

        suspend fun finalizeInProgressRecording(
            recordingId: UUID,
            durationMs: Long,
            title: String? = null,
        ): Recording? {
            val existing = recordingDao.getRecording(recordingId) ?: return null
            val updated =
                existing.copy(
                    title = title ?: existing.title,
                    durationMs = durationMs,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                )
            recordingDao.update(updated)
            return updated
        }

        suspend fun deleteInProgressRecording(recordingId: UUID) {
            val existing = recordingDao.getRecording(recordingId) ?: return
            if (existing.status == RecordingStatus.RECORDING) {
                recordingDao.deleteById(recordingId)
            }
        }

        suspend fun insert(recording: Recording) = recordingDao.insert(recording)

        suspend fun update(recording: Recording) = recordingDao.update(recording)

        suspend fun updateStatus(
            id: UUID,
            status: RecordingStatus,
        ) = recordingDao.updateStatus(id, status)

        suspend fun updateStatusWithError(
            id: UUID,
            status: RecordingStatus,
            errorMessage: String?,
        ) = recordingDao.updateStatusWithError(id, status, errorMessage)

        suspend fun updateTitle(
            id: UUID,
            title: String,
        ) = recordingDao.updateTitle(id, title)

        suspend fun updateDuration(
            id: UUID,
            durationMs: Long,
        ) = recordingDao.updateDuration(id, durationMs)

        suspend fun updateExportInfo(
            id: UUID,
            path: String,
        ) = recordingDao.updateExportInfo(id, path, Date())

        suspend fun delete(recording: Recording) = recordingDao.delete(recording)

        suspend fun deleteById(id: UUID) = recordingDao.deleteById(id)

        suspend fun getTranscripts(recordingIds: List<UUID>): Map<UUID, Transcript> =
            if (recordingIds.isEmpty()) {
                emptyMap()
            } else {
                transcriptDao.getTranscripts(recordingIds).associateBy { it.recordingId }
            }

        suspend fun getTranscript(recordingId: UUID): Transcript? = transcriptDao.getTranscript(recordingId)

        fun getTranscriptFlow(recordingId: UUID): Flow<RepositoryFlowState<Transcript?>> =
            transcriptDao.getTranscriptFlow(recordingId).catchRepositoryFlowState(TAG, null)

        suspend fun getTranscriptTimings(recordingId: UUID): List<TranscriptTiming> =
            transcriptDao.getTranscriptTimings(recordingId)

        fun getTranscriptTimingsFlow(recordingId: UUID): Flow<RepositoryFlowState<List<TranscriptTiming>>> =
            transcriptDao.getTranscriptTimingsFlow(recordingId).catchRepositoryFlowState(TAG, emptyList())

        suspend fun getStructuredOutcomeSnapshot(recordingId: UUID): StructuredOutcomeSnapshot? =
            structuredOutcomeSnapshotDao.getSnapshot(recordingId)?.toModel()

        fun getStructuredOutcomeSnapshotFlow(recordingId: UUID): Flow<RepositoryFlowState<StructuredOutcomeSnapshot?>> =
            structuredOutcomeSnapshotDao
                .getSnapshotFlow(recordingId)
                .map { it?.toModel() }
                .catchRepositoryFlowState(TAG, null)

        suspend fun saveTranscript(transcript: Transcript) {
            val existing = transcriptDao.getTranscript(transcript.recordingId)
            transcriptDao.insert(
                mergePipelineTranscript(
                    transcript = transcript,
                    existing = existing,
                    clearManualCorrection = false,
                ),
            )
        }

        suspend fun saveTranscriptWithTiming(
            transcript: Transcript,
            timings: List<TranscriptTiming>,
        ) {
            database.withTransaction {
                val existing = transcriptDao.getTranscript(transcript.recordingId)
                transcriptDao.insert(
                    mergePipelineTranscript(
                        transcript = transcript,
                        existing = existing,
                        clearManualCorrection = true,
                    ),
                )
                transcriptDao.deleteTimingsByRecordingId(transcript.recordingId)
                if (timings.isNotEmpty()) {
                    transcriptDao.insertTimings(timings)
                }
            }
        }

        suspend fun updateRawText(
            recordingId: UUID,
            rawText: String,
        ) = transcriptDao.updateRawText(recordingId, rawText)

        suspend fun updateProcessedText(
            recordingId: UUID,
            processedText: String,
            mode: String,
        ) = transcriptDao.updateProcessedText(recordingId, processedText, mode)

        suspend fun saveManualCorrection(
            recordingId: UUID,
            correctedText: String,
            sourceText: String,
        ) =
            transcriptDao.updateManualCorrection(
                recordingId = recordingId,
                manualCorrectionText = correctedText,
                manualCorrectionSourceText = sourceText,
            )

        suspend fun clearManualCorrection(
            recordingId: UUID,
        ) =
            transcriptDao.updateManualCorrection(
                recordingId = recordingId,
                manualCorrectionText = null,
                manualCorrectionSourceText = null,
            )

        suspend fun updateSummary(
            recordingId: UUID,
            summary: String,
        ) = transcriptDao.updateSummary(recordingId, summary)

        suspend fun saveStructuredOutcomeSuccess(
            recordingId: UUID,
            sourceTranscriptRevision: String,
            tasks: List<String>,
            decisions: List<String>,
            followUps: List<String>,
        ) {
            val now = Date()
            structuredOutcomeSnapshotDao.insert(
                StructuredOutcomeSnapshot(
                    recordingId = recordingId,
                    sourceTranscriptRevision = sourceTranscriptRevision,
                    generationStatus = StructuredOutcomeGenerationStatus.READY,
                    generatedAt = now,
                    lastAttemptedAt = now,
                    failureMessage = null,
                    tasks = tasks,
                    decisions = decisions,
                    followUps = followUps,
                ).toEntity(),
            )
        }

        suspend fun saveStructuredOutcomeFailure(
            recordingId: UUID,
            sourceTranscriptRevision: String,
            failureMessage: String,
        ) {
            val now = Date()
            val existing = structuredOutcomeSnapshotDao.getSnapshot(recordingId)?.toModel()
            val snapshot =
                if (existing?.hasReadyPayload == true) {
                    existing.copy(
                        generationStatus = StructuredOutcomeGenerationStatus.FAILED,
                        lastAttemptedAt = now,
                        failureMessage = failureMessage,
                    )
                } else {
                    StructuredOutcomeSnapshot(
                        recordingId = recordingId,
                        sourceTranscriptRevision = sourceTranscriptRevision,
                        generationStatus = StructuredOutcomeGenerationStatus.FAILED,
                        generatedAt = null,
                        lastAttemptedAt = now,
                        failureMessage = failureMessage,
                    )
                }

            structuredOutcomeSnapshotDao.insert(snapshot.toEntity())
        }

        suspend fun deleteAll() = recordingDao.deleteAll()

        // Transactional operations

        /**
         * Create a recording with its transcript atomically.
         * Both succeed or both fail - prevents orphaned records.
         */
        suspend fun createRecordingWithTranscript(
            recording: Recording,
            transcript: Transcript,
            timings: List<TranscriptTiming> = emptyList(),
        ): Recording =
            database.withTransaction {
                recordingDao.insert(recording)
                transcriptDao.insert(transcript)
                if (timings.isNotEmpty()) {
                    transcriptDao.insertTimings(timings)
                }
                recording
            }

        /**
         * Delete multiple recordings in a transaction.
         * Processes in batches of 100 to respect SQLite variable limits.
         *
         * Note: Associated transcripts are automatically deleted via CASCADE.
         */
        suspend fun deleteRecordings(ids: List<UUID>) {
            database.withTransaction {
                ids.chunked(999).forEach { batch ->
                    recordingDao.deleteByIds(batch)
                }
            }
        }

        private fun mergePipelineTranscript(
            transcript: Transcript,
            existing: Transcript?,
            clearManualCorrection: Boolean,
        ): Transcript =
            transcript.copy(
                manualCorrectionText = if (clearManualCorrection) null else existing?.manualCorrectionText,
                manualCorrectionSourceText = if (clearManualCorrection) null else existing?.manualCorrectionSourceText,
            )
    }
