package dev.chirpboard.app.data.repository

import androidx.room.withTransaction
import dev.chirpboard.app.data.dao.RecordingEnhancementSnapshotDao
import dev.chirpboard.app.data.dao.RecordingDao
import dev.chirpboard.app.data.dao.StructuredOutcomeSnapshotDao
import dev.chirpboard.app.data.dao.TranscriptDao
import dev.chirpboard.app.data.db.AppDatabase
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.RecordingEnhancementSnapshotEntity
import dev.chirpboard.app.data.entity.RecordingTag
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.entity.toEntity
import dev.chirpboard.app.data.entity.toModel
import dev.chirpboard.app.data.model.EnhancementSubworkStatus
import dev.chirpboard.app.data.model.RecordingEnhancementExecutionSnapshot
import dev.chirpboard.app.data.model.RecordingEnhancementIntent
import dev.chirpboard.app.data.model.RecordingEnhancementResult
import dev.chirpboard.app.data.model.RecordingEnhancementSubworkState
import dev.chirpboard.app.data.model.RecordingEnhancementSnapshot
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.StructuredOutcomeGenerationStatus
import dev.chirpboard.app.data.model.StructuredOutcomeSnapshot
import dev.chirpboard.app.data.model.TranscriptPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RecordingStatusTransitionResult {
    data object TransitionApplied : RecordingStatusTransitionResult

    data class AlreadyTerminal(
        val currentStatus: RecordingStatus,
    ) : RecordingStatusTransitionResult

    data class RejectedStaleState(
        val currentStatus: RecordingStatus,
    ) : RecordingStatusTransitionResult

    data object MissingRecording : RecordingStatusTransitionResult
}

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
        private val enhancementSnapshotDao: RecordingEnhancementSnapshotDao,
    ) {
        companion object {
            private const val TAG = "RecordingRepository"
            private const val SQLITE_BIND_LIMIT = 900
            private const val DEFAULT_SEARCH_LIMIT = 100
            private const val MAX_SEARCH_LIMIT = 500
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

        fun searchRecordings(
            query: String,
            limit: Int = DEFAULT_SEARCH_LIMIT,
        ): Flow<RepositoryFlowState<List<Recording>>> =
            recordingDao
                .searchRecordings(query, limit.coerceIn(1, MAX_SEARCH_LIMIT))
                .catchRepositoryFlowState(TAG, emptyList())

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
            database.withTransaction {
                recordingDao.insert(recording)
                applyProfileDefaultTags(recording.id, profileId)
            }
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
            database.withTransaction {
                recordingDao.insert(recording)
                applyProfileDefaultTags(recording.id, profileId)
            }
            return recording
        }

        suspend fun finalizeInProgressRecording(
            recordingId: UUID,
            durationMs: Long,
            title: String? = null,
        ): Recording? =
            database.withTransaction {
                val updated =
                    recordingDao.finalizeInProgressIfCurrent(
                        id = recordingId,
                        durationMs = durationMs,
                        title = title,
                    )
                recordingDao.getRecording(recordingId)?.takeIf { updated == 1 || it.status != RecordingStatus.RECORDING }
            }

        suspend fun deleteInProgressRecording(recordingId: UUID) {
            recordingDao.deleteByIdAndStatus(recordingId, RecordingStatus.RECORDING)
        }

        /**
         * Deletes an in-progress recording after an explicit user discard or keep-files action.
         * Automatic stop/finalize failures should preserve the row for recovery.
         */
        suspend fun deleteAbandonedInProgressRecording(recordingId: UUID) {
            deleteInProgressRecording(recordingId)
        }

        suspend fun insert(recording: Recording) = recordingDao.insert(recording)

        suspend fun update(recording: Recording) = recordingDao.update(recording)

        suspend fun updateStatus(
            id: UUID,
            status: RecordingStatus,
        ): RecordingStatusTransitionResult =
            transitionRecordingStatus(
                id = id,
                destinationStatus = status,
                allowedSourceStatuses = defaultAllowedSourceStatuses(status),
                errorMessage = null,
            )

        suspend fun updateStatusWithError(
            id: UUID,
            status: RecordingStatus,
            errorMessage: String?,
        ): RecordingStatusTransitionResult =
            transitionRecordingStatus(
                id = id,
                destinationStatus = status,
                allowedSourceStatuses = defaultAllowedSourceStatuses(status),
                errorMessage = errorMessage,
            )

        suspend fun transitionRecordingStatus(
            id: UUID,
            destinationStatus: RecordingStatus,
            allowedSourceStatuses: List<RecordingStatus>,
            errorMessage: String? = null,
        ): RecordingStatusTransitionResult =
            database.withTransaction {
                transitionRecordingStatusLocked(
                    id = id,
                    destinationStatus = destinationStatus,
                    allowedSourceStatuses = allowedSourceStatuses,
                    errorMessage = errorMessage,
                )
            }

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
                recordingIds.distinct()
                    .chunked(SQLITE_BIND_LIMIT)
                    .flatMap { batch -> transcriptDao.getTranscripts(batch) }
                    .associateBy { it.recordingId }
            }

        fun getTranscriptPreviewsFlow(
            recordingIds: List<UUID>,
            previewLimit: Int,
        ): Flow<RepositoryFlowState<Map<UUID, TranscriptPreview>>> {
            val chunks = recordingIds.distinct().chunked(SQLITE_BIND_LIMIT)
            if (chunks.isEmpty()) {
                return flowOf(RepositoryFlowState(emptyMap()))
            }
            val chunkFlows = chunks.map { batch -> transcriptDao.getTranscriptPreviewsFlow(batch, previewLimit) }
            return combine(chunkFlows) { chunkPreviews ->
                chunkPreviews
                    .flatMap { previews -> previews }
                    .associateBy { it.recordingId }
            }.catchRepositoryFlowState(TAG, emptyMap())
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

        suspend fun claimTranscriptionExecution(
            recordingId: UUID,
            executionToken: String,
            status: RecordingStatus = RecordingStatus.PENDING_TRANSCRIPTION,
            errorMessage: String? = null,
        ) {
            recordingDao.updateStatusWithTranscriptionToken(
                id = recordingId,
                status = status,
                errorMessage = errorMessage,
                executionToken = executionToken,
            )
        }

        suspend fun beginTranscriptionExecution(
            recordingId: UUID,
            executionToken: String,
        ): Recording? =
            database.withTransaction {
                val recording = recordingDao.getRecording(recordingId) ?: return@withTransaction null
                if (
                    recording.status != RecordingStatus.PENDING_TRANSCRIPTION ||
                    recording.transcriptionExecutionToken != executionToken
                ) {
                    return@withTransaction null
                }

                val updated =
                    recordingDao.updateStatusForTranscriptionExecution(
                        id = recordingId,
                        expectedStatus = RecordingStatus.PENDING_TRANSCRIPTION,
                        executionToken = executionToken,
                        newStatus = RecordingStatus.TRANSCRIBING,
                        errorMessage = null,
                    )
                if (updated == 0) {
                    null
                } else {
                    recording.copy(status = RecordingStatus.TRANSCRIBING, errorMessage = null)
                }
            }

        suspend fun failTranscriptionExecution(
            recordingId: UUID,
            executionToken: String,
            status: RecordingStatus,
            errorMessage: String,
        ): Boolean =
            recordingDao.updateStatusForTranscriptionExecution(
                id = recordingId,
                expectedStatus = RecordingStatus.TRANSCRIBING,
                executionToken = executionToken,
                newStatus = status,
                errorMessage = errorMessage,
            ) > 0

        suspend fun commitTranscriptionResult(
            transcript: Transcript,
            timings: List<TranscriptTiming>,
            enhancementIntent: RecordingEnhancementIntent?,
        ): RecordingStatusTransitionResult =
            database.withTransaction {
                val currentStatus =
                    recordingDao.getStatus(transcript.recordingId)
                        ?: return@withTransaction RecordingStatusTransitionResult.MissingRecording
                if (currentStatus != RecordingStatus.TRANSCRIBING) {
                    return@withTransaction rejectedTransitionForCurrentStatus(currentStatus)
                }

                val now = Date()
                val existing = transcriptDao.getTranscript(transcript.recordingId)
                val mergedTranscript =
                    mergePipelineTranscript(
                        transcript = transcript.copy(updatedAt = now),
                        existing = existing,
                        clearManualCorrection = true,
                    )
                transcriptDao.insert(mergedTranscript)
                transcriptDao.deleteTimingsByRecordingId(transcript.recordingId)
                if (timings.isNotEmpty()) {
                    transcriptDao.insertTimings(timings)
                }

                if (enhancementIntent?.hasRequestedWork == true) {
                    enhancementSnapshotDao.upsert(
                        enhancementIntent.toSnapshotEntity(
                            recordingId = transcript.recordingId,
                            transcript = mergedTranscript,
                            enhancementExecutionToken = null,
                            createdAt = now,
                        ),
                    )
                    transitionRecordingStatusLocked(
                        id = transcript.recordingId,
                        destinationStatus = RecordingStatus.PENDING_ENHANCEMENT,
                        allowedSourceStatuses = listOf(RecordingStatus.TRANSCRIBING),
                        errorMessage = null,
                    )
                } else {
                    enhancementSnapshotDao.deleteByRecordingId(transcript.recordingId)
                    transitionRecordingStatusLocked(
                        id = transcript.recordingId,
                        destinationStatus = RecordingStatus.COMPLETED,
                        allowedSourceStatuses = listOf(RecordingStatus.TRANSCRIBING),
                        errorMessage = null,
                    )
                }
            }

        suspend fun commitTranscriptionResult(
            transcript: Transcript,
            timings: List<TranscriptTiming>,
            enhancementIntent: RecordingEnhancementIntent?,
            expectedExecutionToken: String,
            enhancementExecutionToken: String?,
        ): Boolean =
            database.withTransaction {
                val recording = recordingDao.getRecording(transcript.recordingId) ?: return@withTransaction false
                if (
                    recording.status != RecordingStatus.TRANSCRIBING ||
                    recording.transcriptionExecutionToken != expectedExecutionToken
                ) {
                    return@withTransaction false
                }

                val now = Date()
                val existing = transcriptDao.getTranscript(transcript.recordingId)
                val mergedTranscript =
                    mergePipelineTranscript(
                        transcript = transcript.copy(updatedAt = now),
                        existing = existing,
                        clearManualCorrection = true,
                    )
                transcriptDao.insert(mergedTranscript)
                transcriptDao.deleteTimingsByRecordingId(transcript.recordingId)
                if (timings.isNotEmpty()) {
                    transcriptDao.insertTimings(timings)
                }

                if (enhancementIntent?.hasRequestedWork == true) {
                    enhancementSnapshotDao.upsert(
                        enhancementIntent.toSnapshotEntity(
                            recordingId = transcript.recordingId,
                            transcript = mergedTranscript,
                            enhancementExecutionToken = enhancementExecutionToken,
                            createdAt = now,
                        ),
                    )
                    recordingDao.updateStatusWithTranscriptionToken(
                        id = transcript.recordingId,
                        status = RecordingStatus.PENDING_ENHANCEMENT,
                        errorMessage = null,
                        executionToken = null,
                    )
                } else {
                    enhancementSnapshotDao.deleteByRecordingId(transcript.recordingId)
                    recordingDao.updateStatusWithTranscriptionToken(
                        id = transcript.recordingId,
                        status = RecordingStatus.COMPLETED,
                        errorMessage = null,
                        executionToken = null,
                    )
                }
                true
            }

        suspend fun claimEnhancementExecution(
            recordingId: UUID,
            executionToken: String,
            status: RecordingStatus = RecordingStatus.PENDING_ENHANCEMENT,
            errorMessage: String? = null,
        ): Boolean =
            database.withTransaction {
                val snapshot = enhancementSnapshotDao.getSnapshot(recordingId) ?: return@withTransaction false
                val transition =
                    transitionRecordingStatusLocked(
                        id = recordingId,
                        destinationStatus = status,
                        allowedSourceStatuses =
                            when (status) {
                                RecordingStatus.PENDING_ENHANCEMENT ->
                                    listOf(
                                        RecordingStatus.PENDING_ENHANCEMENT,
                                        RecordingStatus.ENHANCING,
                                        RecordingStatus.FAILED,
                                    )

                                else -> listOf(status)
                            },
                        errorMessage = errorMessage,
                    )
                if (transition != RecordingStatusTransitionResult.TransitionApplied) {
                    return@withTransaction false
                }
                enhancementSnapshotDao.upsert(
                    snapshot.copy(
                        activeEnhancementExecutionToken = executionToken,
                        lastErrorMessage = null,
                    ),
                )
                true
            }

        suspend fun hasUnresolvedEnhancementSnapshot(recordingId: UUID): Boolean =
            enhancementSnapshotDao.getSnapshot(recordingId)?.toModel()?.hasUnresolvedWork == true

        suspend fun deleteEnhancementSnapshot(recordingId: UUID) {
            enhancementSnapshotDao.deleteByRecordingId(recordingId)
        }

        suspend fun beginEnhancement(recordingId: UUID): RecordingEnhancementSnapshot? =
            database.withTransaction {
                val recording = recordingDao.getRecording(recordingId) ?: return@withTransaction null
                val transcript = transcriptDao.getTranscript(recordingId) ?: return@withTransaction null
                val snapshot = enhancementSnapshotDao.getSnapshot(recordingId) ?: return@withTransaction null

                val transition =
                    transitionRecordingStatusLocked(
                        id = recordingId,
                        destinationStatus = RecordingStatus.ENHANCING,
                        allowedSourceStatuses = listOf(RecordingStatus.PENDING_ENHANCEMENT),
                        errorMessage = null,
                    )
                if (transition != RecordingStatusTransitionResult.TransitionApplied) {
                    return@withTransaction null
                }
                val attemptedAt = Date()
                val attemptedSnapshot =
                    snapshot.copy(
                        lastAttemptedAt = attemptedAt,
                        lastErrorMessage = null,
                    )
                enhancementSnapshotDao.upsert(attemptedSnapshot)

                RecordingEnhancementSnapshot(
                    recording = recording.copy(status = RecordingStatus.ENHANCING, errorMessage = null),
                    transcript = transcript,
                    execution = attemptedSnapshot.toModel(),
                )
            }

        suspend fun beginEnhancement(
            recordingId: UUID,
            executionToken: String,
        ): RecordingEnhancementSnapshot? =
            database.withTransaction {
                val recording = recordingDao.getRecording(recordingId) ?: return@withTransaction null
                val transcript = transcriptDao.getTranscript(recordingId) ?: return@withTransaction null
                val snapshot = enhancementSnapshotDao.getSnapshot(recordingId) ?: return@withTransaction null
                if (snapshot.activeEnhancementExecutionToken != executionToken) {
                    return@withTransaction null
                }

                val updated =
                    recordingDao.updateStatusIfCurrent(
                        id = recordingId,
                        expectedStatus = RecordingStatus.PENDING_ENHANCEMENT,
                        newStatus = RecordingStatus.ENHANCING,
                        errorMessage = null,
                    )
                if (updated == 0 && recording.status != RecordingStatus.ENHANCING) {
                    return@withTransaction null
                }
                enhancementSnapshotDao.markAttempt(recordingId, executionToken)

                RecordingEnhancementSnapshot(
                    recording = recording.copy(status = RecordingStatus.ENHANCING, errorMessage = null),
                    transcript = transcript,
                    execution = snapshot.copy(lastAttemptedAt = Date()).toModel(),
                )
            }

        suspend fun completeEnhancement(
            recordingId: UUID,
            result: RecordingEnhancementResult,
        ): Boolean =
            completeEnhancementLocked(
                recordingId = recordingId,
                result = result,
                snapshotGuard = { true },
                transcriptGuard = { true },
            )

        suspend fun completeEnhancement(
            recordingId: UUID,
            executionToken: String,
            sourceTranscriptRevision: String,
            result: RecordingEnhancementResult,
        ): Boolean =
            completeEnhancementLocked(
                recordingId = recordingId,
                result = result,
                snapshotGuard = { snapshot ->
                    snapshot.activeEnhancementExecutionToken == executionToken &&
                        snapshot.sourceTranscriptRevision == sourceTranscriptRevision
                },
                transcriptGuard = { transcript -> transcript.sourceRevision() == sourceTranscriptRevision },
            )

        private suspend fun completeEnhancementLocked(
            recordingId: UUID,
            result: RecordingEnhancementResult,
            snapshotGuard: (RecordingEnhancementSnapshotEntity) -> Boolean,
            transcriptGuard: (Transcript) -> Boolean,
        ): Boolean =
            database.withTransaction {
                val now = Date()
                if (recordingDao.getStatus(recordingId) != RecordingStatus.ENHANCING) {
                    return@withTransaction false
                }
                val snapshot = enhancementSnapshotDao.getSnapshot(recordingId) ?: return@withTransaction false
                if (!snapshotGuard(snapshot)) {
                    return@withTransaction false
                }
                val transcript = transcriptDao.getTranscript(recordingId) ?: return@withTransaction false
                if (!transcriptGuard(transcript)) {
                    return@withTransaction false
                }

                if (
                    result.processedText != null ||
                    result.processingMode != null ||
                    result.summary != null
                ) {
                    transcriptDao.insert(
                        transcript.copy(
                            processedText = result.processedText ?: transcript.processedText,
                            processingMode = result.processingMode ?: transcript.processingMode,
                            summary = result.summary ?: transcript.summary,
                            updatedAt = now,
                        ),
                    )
                }
                result.title?.let { title ->
                    recordingDao.updateTitle(recordingId, title)
                }

                val updatedSnapshot = snapshot.applyResult(result, now)
                val unresolvedError = updatedSnapshot.firstUnresolvedError()
                if (updatedSnapshot.toModel().hasUnresolvedWork) {
                    enhancementSnapshotDao.upsert(updatedSnapshot.copy(lastErrorMessage = unresolvedError))
                    transitionRecordingStatusLocked(
                        id = recordingId,
                        destinationStatus = RecordingStatus.FAILED,
                        allowedSourceStatuses = listOf(RecordingStatus.ENHANCING),
                        errorMessage = unresolvedError ?: "Enhancement failed",
                    )
                } else {
                    enhancementSnapshotDao.deleteByRecordingId(recordingId)
                    transitionRecordingStatusLocked(
                        id = recordingId,
                        destinationStatus = RecordingStatus.COMPLETED,
                        allowedSourceStatuses = listOf(RecordingStatus.ENHANCING),
                        errorMessage = null,
                    )
                }
                true
            }

        suspend fun skipEnhancement(recordingId: UUID): Boolean =
            skipEnhancementLocked(
                recordingId = recordingId,
                snapshotGuard = { true },
            )

        suspend fun skipEnhancement(
            recordingId: UUID,
            executionToken: String,
        ): Boolean =
            skipEnhancementLocked(
                recordingId = recordingId,
                snapshotGuard = { snapshot -> snapshot.activeEnhancementExecutionToken == executionToken },
            )

        private suspend fun skipEnhancementLocked(
            recordingId: UUID,
            snapshotGuard: (RecordingEnhancementSnapshotEntity) -> Boolean,
        ): Boolean =
            database.withTransaction {
                if (recordingDao.getStatus(recordingId) != RecordingStatus.ENHANCING) {
                    return@withTransaction false
                }
                val snapshot = enhancementSnapshotDao.getSnapshot(recordingId) ?: return@withTransaction false
                if (!snapshotGuard(snapshot)) {
                    return@withTransaction false
                }
                enhancementSnapshotDao.deleteByRecordingId(recordingId)
                transitionRecordingStatusLocked(
                    id = recordingId,
                    destinationStatus = RecordingStatus.COMPLETED,
                    allowedSourceStatuses = listOf(RecordingStatus.ENHANCING),
                    errorMessage = null,
                )
                true
            }

        suspend fun failEnhancement(
            recordingId: UUID,
            errorMessage: String,
        ): Boolean = failEnhancement(recordingId, null, errorMessage)

        suspend fun failEnhancement(
            recordingId: UUID,
            executionToken: String?,
            errorMessage: String,
        ): Boolean =
            database.withTransaction {
                val currentStatus =
                    recordingDao.getStatus(recordingId)
                        ?: return@withTransaction false
                if (currentStatus != RecordingStatus.ENHANCING && currentStatus != RecordingStatus.PENDING_ENHANCEMENT) {
                    return@withTransaction false
                }
                val snapshot = enhancementSnapshotDao.getSnapshot(recordingId)
                if (snapshot == null) {
                    transitionRecordingStatusLocked(
                        id = recordingId,
                        destinationStatus = RecordingStatus.FAILED,
                        allowedSourceStatuses = listOf(currentStatus),
                        errorMessage = errorMessage,
                    )
                    return@withTransaction true
                }
                if (executionToken != null && snapshot.activeEnhancementExecutionToken != executionToken) {
                    return@withTransaction false
                }
                enhancementSnapshotDao.upsert(snapshot.markUnresolvedFailed(errorMessage, Date()))
                transitionRecordingStatusLocked(
                    id = recordingId,
                    destinationStatus = RecordingStatus.FAILED,
                    allowedSourceStatuses = listOf(currentStatus),
                    errorMessage = errorMessage,
                )
                true
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
                applyProfileDefaultTags(recording.id, recording.profileId)
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
                ids.distinct().chunked(SQLITE_BIND_LIMIT).forEach { batch ->
                    recordingDao.deleteByIds(batch)
                }
            }
        }

        private suspend fun applyProfileDefaultTags(
            recordingId: UUID,
            profileId: UUID?,
        ) {
            if (profileId == null) {
                return
            }
            val defaultTagIds = database.profileDao().getDefaultTagIds(profileId)
            if (defaultTagIds.isNotEmpty()) {
                database
                    .tagDao()
                    .addTagsToRecording(defaultTagIds.map { tagId -> RecordingTag(recordingId, tagId) })
            }
        }

        private suspend fun transitionRecordingStatusLocked(
            id: UUID,
            destinationStatus: RecordingStatus,
            allowedSourceStatuses: List<RecordingStatus>,
            errorMessage: String?,
        ): RecordingStatusTransitionResult {
            if (allowedSourceStatuses.isEmpty()) {
                return recordingDao.getStatus(id)?.let(::rejectedTransitionForCurrentStatus)
                    ?: RecordingStatusTransitionResult.MissingRecording
            }

            val updated =
                recordingDao.updateStatusWithErrorIfCurrentIn(
                    id = id,
                    status = destinationStatus,
                    errorMessage = errorMessage,
                    allowedStatuses = allowedSourceStatuses,
                )
            if (updated == 1) {
                return RecordingStatusTransitionResult.TransitionApplied
            }

            return recordingDao.getStatus(id)?.let(::rejectedTransitionForCurrentStatus)
                ?: RecordingStatusTransitionResult.MissingRecording
        }

        private fun rejectedTransitionForCurrentStatus(currentStatus: RecordingStatus): RecordingStatusTransitionResult =
            if (currentStatus.isTerminal()) {
                RecordingStatusTransitionResult.AlreadyTerminal(currentStatus)
            } else {
                RecordingStatusTransitionResult.RejectedStaleState(currentStatus)
            }

        private fun RecordingStatus.isTerminal(): Boolean =
            this == RecordingStatus.COMPLETED || this == RecordingStatus.FAILED

        private fun defaultAllowedSourceStatuses(destinationStatus: RecordingStatus): List<RecordingStatus> =
            when (destinationStatus) {
                RecordingStatus.RECORDING -> emptyList()
                RecordingStatus.PENDING_TRANSCRIPTION ->
                    listOf(
                        RecordingStatus.RECORDING,
                        RecordingStatus.TRANSCRIBING,
                        RecordingStatus.FAILED,
                    )
                RecordingStatus.TRANSCRIBING -> listOf(RecordingStatus.PENDING_TRANSCRIPTION)
                RecordingStatus.PENDING_ENHANCEMENT ->
                    listOf(
                        RecordingStatus.TRANSCRIBING,
                        RecordingStatus.ENHANCING,
                    )
                RecordingStatus.ENHANCING -> listOf(RecordingStatus.PENDING_ENHANCEMENT)
                RecordingStatus.COMPLETED ->
                    listOf(
                        RecordingStatus.TRANSCRIBING,
                        RecordingStatus.PENDING_ENHANCEMENT,
                        RecordingStatus.ENHANCING,
                    )
                RecordingStatus.FAILED ->
                    listOf(
                        RecordingStatus.PENDING_TRANSCRIPTION,
                        RecordingStatus.TRANSCRIBING,
                        RecordingStatus.PENDING_ENHANCEMENT,
                        RecordingStatus.ENHANCING,
                    )
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

        private fun RecordingEnhancementIntent.toSnapshotEntity(
            recordingId: UUID,
            transcript: Transcript,
            enhancementExecutionToken: String?,
            createdAt: Date,
        ): RecordingEnhancementSnapshotEntity {
            val processingRequested = processingModeId != null
            return RecordingEnhancementSnapshotEntity(
                recordingId = recordingId,
                sourceTranscriptRevision = transcript.sourceRevision(),
                sourceProcessedTextRevision = transcript.processedTextRevision(),
                processingModeRequested = processingRequested,
                processingModeId = processingModeId,
                processingModeLabel = processingModeLabel,
                processingModeType = processingModeType,
                processingModePrompt = processingModePrompt,
                processingModeStatus = if (processingRequested) EnhancementSubworkStatus.PENDING else EnhancementSubworkStatus.SKIPPED,
                processingModeErrorMessage = null,
                titleRequested = autoTitle,
                titleStatus = if (autoTitle) EnhancementSubworkStatus.PENDING else EnhancementSubworkStatus.SKIPPED,
                titleErrorMessage = null,
                summaryRequested = autoSummary,
                summaryStatus = if (autoSummary) EnhancementSubworkStatus.PENDING else EnhancementSubworkStatus.SKIPPED,
                summaryErrorMessage = null,
                llmProviderId = llmProviderId,
                llmModelId = llmModelId,
                activeEnhancementExecutionToken = enhancementExecutionToken,
                legacyRequiresResolution = legacyRequiresResolution,
                createdAt = createdAt,
                lastAttemptedAt = null,
                lastErrorMessage = null,
            )
        }

        private fun RecordingEnhancementSnapshotEntity.toModel(): RecordingEnhancementExecutionSnapshot =
            RecordingEnhancementExecutionSnapshot(
                recordingId = recordingId,
                schemaVersion = schemaVersion,
                sourceTranscriptRevision = sourceTranscriptRevision,
                sourceProcessedTextRevision = sourceProcessedTextRevision,
                processingModeId = processingModeId,
                processingModeLabel = processingModeLabel,
                processingModeType = processingModeType,
                processingModePrompt = processingModePrompt,
                processingMode =
                    RecordingEnhancementSubworkState(
                        requested = processingModeRequested,
                        status = processingModeStatus,
                        errorMessage = processingModeErrorMessage,
                    ),
                title =
                    RecordingEnhancementSubworkState(
                        requested = titleRequested,
                        status = titleStatus,
                        errorMessage = titleErrorMessage,
                    ),
                summary =
                    RecordingEnhancementSubworkState(
                        requested = summaryRequested,
                        status = summaryStatus,
                        errorMessage = summaryErrorMessage,
                    ),
                llmProviderId = llmProviderId,
                llmModelId = llmModelId,
                activeEnhancementExecutionToken = activeEnhancementExecutionToken,
                legacyRequiresResolution = legacyRequiresResolution,
                createdAt = createdAt,
                lastAttemptedAt = lastAttemptedAt,
                lastErrorMessage = lastErrorMessage,
            )

        private fun Transcript.sourceRevision(): String =
            listOf(
                rawText,
                manualCorrectionText.orEmpty(),
                manualCorrectionSourceText.orEmpty(),
            ).joinToString(separator = "|")

        private fun Transcript.processedTextRevision(): String? =
            processedText?.let { "${processingMode.orEmpty()}|$it" }

        private fun RecordingEnhancementSnapshotEntity.applyResult(
            result: RecordingEnhancementResult,
            now: Date,
        ): RecordingEnhancementSnapshotEntity =
            copy(
                processingModeStatus =
                    result.processingModeStatus
                        ?: processingModeStatus,
                processingModeErrorMessage =
                    result.processingModeError
                        ?: if (result.processingModeStatus == EnhancementSubworkStatus.SUCCEEDED) null else processingModeErrorMessage,
                titleStatus =
                    result.titleStatus
                        ?: titleStatus,
                titleErrorMessage =
                    result.titleError
                        ?: if (result.titleStatus == EnhancementSubworkStatus.SUCCEEDED) null else titleErrorMessage,
                summaryStatus =
                    result.summaryStatus
                        ?: summaryStatus,
                summaryErrorMessage =
                    result.summaryError
                        ?: if (result.summaryStatus == EnhancementSubworkStatus.SUCCEEDED) null else summaryErrorMessage,
                lastAttemptedAt = now,
            )

        private fun RecordingEnhancementSnapshotEntity.markUnresolvedFailed(
            errorMessage: String,
            now: Date,
        ): RecordingEnhancementSnapshotEntity =
            copy(
                processingModeStatus =
                    if (processingModeRequested && processingModeStatus != EnhancementSubworkStatus.SUCCEEDED) {
                        EnhancementSubworkStatus.FAILED
                    } else {
                        processingModeStatus
                    },
                processingModeErrorMessage =
                    if (processingModeRequested && processingModeStatus != EnhancementSubworkStatus.SUCCEEDED) {
                        errorMessage
                    } else {
                        processingModeErrorMessage
                    },
                titleStatus =
                    if (titleRequested && titleStatus != EnhancementSubworkStatus.SUCCEEDED) {
                        EnhancementSubworkStatus.FAILED
                    } else {
                        titleStatus
                    },
                titleErrorMessage =
                    if (titleRequested && titleStatus != EnhancementSubworkStatus.SUCCEEDED) {
                        errorMessage
                    } else {
                        titleErrorMessage
                    },
                summaryStatus =
                    if (summaryRequested && summaryStatus != EnhancementSubworkStatus.SUCCEEDED) {
                        EnhancementSubworkStatus.FAILED
                    } else {
                        summaryStatus
                    },
                summaryErrorMessage =
                    if (summaryRequested && summaryStatus != EnhancementSubworkStatus.SUCCEEDED) {
                        errorMessage
                    } else {
                        summaryErrorMessage
                    },
                lastAttemptedAt = now,
                lastErrorMessage = errorMessage,
            )

        private fun RecordingEnhancementSnapshotEntity.firstUnresolvedError(): String? =
            listOf(
                processingModeErrorMessage.takeIf {
                    processingModeRequested &&
                        processingModeStatus == EnhancementSubworkStatus.FAILED
                },
                titleErrorMessage.takeIf {
                    titleRequested &&
                        titleStatus == EnhancementSubworkStatus.FAILED
                },
                summaryErrorMessage.takeIf {
                    summaryRequested &&
                        summaryStatus == EnhancementSubworkStatus.FAILED
                },
            ).firstOrNull { !it.isNullOrBlank() }
    }
