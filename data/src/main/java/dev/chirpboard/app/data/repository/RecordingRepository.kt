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
import dev.chirpboard.app.data.model.RecordingLibraryStats
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

            /**
             * Statuses a transcription claim may take ownership from. COMPLETED is excluded so
             * a stale claim can never resurrect a finished recording, and RECORDING is excluded
             * so an in-progress capture is never hijacked before finalize.
             * AWAITING_MANUAL_TRANSCRIPTION is claimable: starting a deliberately skipped or
             * cancelled recording is always an explicit enqueue/retranscribe call (automatic
             * recovery never loads AWAITING rows, see [getPendingRecordings]).
             */
            private val TRANSCRIPTION_CLAIMABLE_STATUSES =
                listOf(
                    RecordingStatus.PENDING_TRANSCRIPTION,
                    RecordingStatus.TRANSCRIBING,
                    RecordingStatus.ENHANCING,
                    RecordingStatus.FAILED,
                    RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION,
                )

            /**
             * Statuses an explicit user-requested re-transcription may claim from. Unlike
             * [TRANSCRIPTION_CLAIMABLE_STATUSES] this includes COMPLETED, because the user is
             * deliberately sending a finished recording back through the pipeline. RECORDING
             * stays excluded so an in-progress capture is never hijacked.
             */
            private val RETRANSCRIPTION_CLAIMABLE_STATUSES =
                TRANSCRIPTION_CLAIMABLE_STATUSES + RecordingStatus.COMPLETED
        }

        fun getAllRecordings(): Flow<RepositoryFlowState<List<Recording>>> =
            recordingDao.getAllRecordings().catchRepositoryFlowState(TAG, emptyList())

        /**
         * Full-table aggregates for the home header stats (DAT-006). [getAllRecordings] is capped
         * at [RecordingDao.HOME_RECORDINGS_LIMIT] rows, so counts derived from it undercount large
         * libraries; this query always reflects every recording.
         */
        fun getLibraryStats(): Flow<RepositoryFlowState<RecordingLibraryStats>> =
            recordingDao
                .getLibraryStats(RecordingStatus.COMPLETED)
                .catchRepositoryFlowState(TAG, RecordingLibraryStats())

        suspend fun getAllAudioPaths(): List<String> = recordingDao.getAllAudioPaths()

        suspend fun getRecording(id: UUID): Recording? = recordingDao.getRecording(id)

        fun getRecordingFlow(id: UUID): Flow<RepositoryFlowState<Recording?>> =
            recordingDao.getRecordingFlow(id).catchRepositoryFlowState(TAG, null)

        fun getRecordingsByStatus(status: RecordingStatus): Flow<RepositoryFlowState<List<Recording>>> =
            recordingDao.getRecordingsByStatus(status).catchRepositoryFlowState(TAG, emptyList())

        /**
         * Recordings eligible for automatic queue recovery. Deliberately excludes
         * AWAITING_MANUAL_TRANSCRIPTION rows (profile Auto Transcribe off / user cancel):
         * those must never be re-enqueued by startup recovery, recover-stuck, or the
         * reconciler — only an explicit user action starts them.
         */
        suspend fun getPendingRecordings(): List<Recording> =
            recordingDao.getRecordingsByStatuses(
                listOf(RecordingStatus.PENDING_TRANSCRIPTION, RecordingStatus.PENDING_ENHANCEMENT),
            )

        /**
         * Whether the recording's profile allows automatic transcription enqueue.
         * Defaults to true when the recording, profile, or association is missing so
         * the gate can never strand a recording that has no explicit opt-out.
         */
        suspend fun isAutoTranscribeEnabled(recordingId: UUID): Boolean {
            val profileId = recordingDao.getRecording(recordingId)?.profileId ?: return true
            return database.profileDao().getProfile(profileId)?.autoTranscribe != false
        }

        /**
         * Moves a queued/running transcription row into the deliberate manual state and
         * clears its execution token, so any in-flight worker holding the old token sees
         * its commit/fail rejected as stale. Applies only from PENDING_TRANSCRIPTION or
         * TRANSCRIBING; COMPLETED/FAILED rows are never regressed.
         */
        suspend fun markAwaitingManualTranscription(recordingId: UUID): Boolean =
            recordingDao.updateStatusWithTranscriptionToken(
                id = recordingId,
                status = RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION,
                errorMessage = null,
                executionToken = null,
                allowedCurrentStatuses =
                    listOf(RecordingStatus.PENDING_TRANSCRIPTION, RecordingStatus.TRANSCRIBING),
                expectedExecutionToken = null,
            ) > 0

        /**
         * Returns a stale TRANSCRIBING row to the queue. Pinned to TRANSCRIBING *and* to the
         * execution token the row was read with, so a re-claim (or the worker's own commit)
         * landing between that read and this write is never overwritten. The token is left
         * as-is; the next claim replaces it.
         */
        suspend fun resetStaleTranscribingToPending(
            id: UUID,
            expectedExecutionToken: String?,
            errorMessage: String?,
        ): Boolean =
            recordingDao.updateStatusWithTranscriptionToken(
                id = id,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                errorMessage = errorMessage,
                executionToken = expectedExecutionToken,
                allowedCurrentStatuses = listOf(RecordingStatus.TRANSCRIBING),
                expectedExecutionToken = expectedExecutionToken,
            ) > 0

        /**
         * Resolves a user-cancelled enhancement to a neutral terminal state: the committed
         * transcript is kept and the row becomes COMPLETED (or AWAITING_MANUAL_TRANSCRIPTION
         * in the defensive case where no transcript exists). The enhancement snapshot is
         * removed so reconciliation never resumes the cancelled work.
         */
        suspend fun resolveCancelledEnhancement(recordingId: UUID): Boolean =
            database.withTransaction {
                val currentStatus = recordingDao.getStatus(recordingId) ?: return@withTransaction false
                if (currentStatus != RecordingStatus.PENDING_ENHANCEMENT &&
                    currentStatus != RecordingStatus.ENHANCING
                ) {
                    return@withTransaction false
                }
                enhancementSnapshotDao.deleteByRecordingId(recordingId)
                val destination =
                    if (transcriptDao.getTranscript(recordingId) != null) {
                        RecordingStatus.COMPLETED
                    } else {
                        RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION
                    }
                val resolved =
                    recordingDao.updateStatusWithTranscriptionToken(
                        id = recordingId,
                        status = destination,
                        errorMessage = null,
                        executionToken = null,
                        allowedCurrentStatuses = listOf(currentStatus),
                        expectedExecutionToken = null,
                    ) > 0
                if (resolved) {
                    // The claim armed the terminal marker; a cancelled enhancement must not
                    // surface as a "ready" notification for work the user stopped.
                    recordingDao.clearPendingTerminalNotification(recordingId, destination)
                }
                resolved
            }

        fun searchRecordings(
            query: String,
            limit: Int = DEFAULT_SEARCH_LIMIT,
        ): Flow<RepositoryFlowState<List<Recording>>> =
            recordingDao
                .searchRecordings(likeContainsPattern(query), limit.coerceIn(1, MAX_SEARCH_LIMIT))
                .catchRepositoryFlowState(TAG, emptyList())

        // LIKE has no literal mode: escape the metacharacters so a user typing "%"/"_" searches
        // for those characters instead of getting wildcard matches over the whole library.
        private fun likeContainsPattern(query: String): String =
            "%" +
                query
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") +
                "%"

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

        /**
         * Finalizes an in-progress recording row. When [audioPath] is non-null the row is also
         * pointed at the real exported file, so it never keeps referencing a temp segment path
         * that cleanup may later delete. Null keeps the existing path.
         */
        suspend fun finalizeInProgressRecording(
            recordingId: UUID,
            durationMs: Long,
            title: String? = null,
            audioPath: String? = null,
        ): Recording? =
            database.withTransaction {
                val updated =
                    recordingDao.finalizeInProgressIfCurrent(
                        id = recordingId,
                        durationMs = durationMs,
                        title = title,
                        audioPath = audioPath,
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

        /** Freeform user note for a recording, or null when none was written (or the row is gone). */
        suspend fun getNotes(id: UUID): String? = recordingDao.getNotes(id)

        /**
         * Writes the freeform user note onto a recording row. Blank text is normalized to NULL so
         * "has a note" checks stay a simple null test everywhere (home glyph, studio section).
         *
         * Touches ONLY the notes column — never status, execution tokens, or finalize-owned
         * fields — so callers may run it concurrently with the stop/finalize pipeline. A write
         * against a deleted row is a harmless no-op (returns false).
         *
         * Seam for future LLM enrichment: an automatic note generator (e.g. a one-line gist from
         * the transcript) should populate notes through this same method, ideally only when the
         * row's notes are still NULL so a user-authored note is never overwritten.
         *
         * @return true when a row was updated.
         */
        suspend fun updateNotes(
            id: UUID,
            notes: String?,
        ): Boolean = recordingDao.updateNotes(id, notes?.takeUnless { it.isBlank() }) > 0

        /**
         * Persists a queue's chosen file-level engine before network or recognizer work starts.
         * Returns the winning row so a concurrent retry always uses the value already stored.
         */
        suspend fun stampTranscriptionEngineIfUnset(
            id: UUID,
            engineId: String,
        ): Recording? =
            database.withTransaction {
                recordingDao.setTranscriptionEngineIfUnset(id, engineId)
                recordingDao.getRecording(id)
            }

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

        suspend fun getPendingTerminalNotifications(): List<Recording> =
            recordingDao.getPendingTerminalNotifications()

        suspend fun clearPendingTerminalNotification(
            recordingId: UUID,
            expectedStatus: RecordingStatus,
        ): Boolean =
            recordingDao.clearPendingTerminalNotification(recordingId, expectedStatus) > 0

        fun getTranscriptFlow(recordingId: UUID): Flow<RepositoryFlowState<Transcript?>> =
            transcriptDao.getTranscriptFlow(recordingId).catchRepositoryFlowState(TAG, null)

        suspend fun getTranscriptTimings(recordingId: UUID): List<TranscriptTiming> =
            transcriptDao.getTranscriptTimings(recordingId)

        fun getTranscriptTimingsFlow(recordingId: UUID): Flow<RepositoryFlowState<List<TranscriptTiming>>> =
            transcriptDao.getTranscriptTimingsFlow(recordingId).catchRepositoryFlowState(TAG, emptyList())

        fun getStructuredOutcomeSnapshotFlow(recordingId: UUID): Flow<RepositoryFlowState<StructuredOutcomeSnapshot?>> =
            structuredOutcomeSnapshotDao
                .getSnapshotFlow(recordingId)
                .map { it?.toModel() }
                .catchRepositoryFlowState(TAG, null)

        suspend fun saveTranscript(transcript: Transcript) {
            database.withTransaction {
                val existing = transcriptDao.getTranscript(transcript.recordingId)
                transcriptDao.insert(
                    mergePipelineTranscript(
                        transcript = transcript,
                        existing = existing,
                        clearManualCorrection = false,
                    ),
                )
            }
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

        /**
         * Claims (or re-claims) transcription queue ownership by stamping a fresh execution
         * token. The claim only applies while the row is in a claimable status; a recording
         * that already reached COMPLETED (or is still RECORDING) can never be regressed by a
         * stale queue pass.
         *
         * @return true when the claim was applied, false when the row was missing or no
         *   longer in a claimable status.
         */
        suspend fun claimTranscriptionExecution(
            recordingId: UUID,
            executionToken: String,
            status: RecordingStatus = RecordingStatus.PENDING_TRANSCRIPTION,
            errorMessage: String? = null,
        ): Boolean =
            recordingDao.claimTranscriptionExecution(
                id = recordingId,
                status = status,
                errorMessage = errorMessage,
                executionToken = executionToken,
                allowedCurrentStatuses = TRANSCRIPTION_CLAIMABLE_STATUSES,
            ) > 0

        /**
         * Claims transcription ownership for an explicit user-requested re-transcription.
         * Identical to [claimTranscriptionExecution] except a COMPLETED recording may also
         * be reset back to PENDING_TRANSCRIPTION.
         *
         * @return true when the claim was applied, false when the row was missing or in a
         *   non-claimable status (e.g. still RECORDING).
         */
        suspend fun claimRetranscriptionExecution(
            recordingId: UUID,
            executionToken: String,
        ): Boolean =
            recordingDao.claimTranscriptionExecution(
                id = recordingId,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                errorMessage = null,
                executionToken = executionToken,
                allowedCurrentStatuses = RETRANSCRIPTION_CLAIMABLE_STATUSES,
            ) > 0

        suspend fun beginTranscriptionExecution(
            recordingId: UUID,
            executionToken: String,
        ): Recording? =
            database.withTransaction {
                val recording = recordingDao.getRecording(recordingId) ?: return@withTransaction null
                if (recording.transcriptionExecutionToken != executionToken) {
                    return@withTransaction null
                }
                if (recording.status == RecordingStatus.TRANSCRIBING) {
                    // A TRANSCRIBING row still carrying this run's token is our own
                    // interrupted execution (process died before commit); resume it
                    // instead of abandoning the row in TRANSCRIBING forever.
                    return@withTransaction recording.copy(errorMessage = null)
                }
                if (recording.status != RecordingStatus.PENDING_TRANSCRIPTION) {
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

        /**
         * Redirects the recording to a replacement audio file only while this transcription
         * run still owns the row and the row still points at [expectedAudioPath]. Callers must
         * finish writing the replacement before this swap, then may delete the old duplicate.
         */
        suspend fun swapAudioPathForTranscriptionExecution(
            recordingId: UUID,
            executionToken: String,
            expectedAudioPath: String,
            newAudioPath: String,
        ): Boolean =
            recordingDao.swapAudioPathForTranscriptionExecution(
                id = recordingId,
                executionToken = executionToken,
                expectedAudioPath = expectedAudioPath,
                newAudioPath = newAudioPath,
            ) > 0

        suspend fun rerouteTranscriptionEngineForExecution(
            recordingId: UUID,
            executionToken: String,
            expectedEngineId: String,
            newEngineId: String,
        ): Boolean =
            recordingDao.rerouteTranscriptionEngineForExecution(
                id = recordingId,
                executionToken = executionToken,
                expectedEngineId = expectedEngineId,
                newEngineId = newEngineId,
            ) > 0

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

                val destinationStatus =
                    persistTranscriptionResultLocked(
                        transcript = transcript,
                        timings = timings,
                        enhancementIntent = enhancementIntent,
                        enhancementExecutionToken = null,
                    )
                transitionRecordingStatusLocked(
                    id = transcript.recordingId,
                    destinationStatus = destinationStatus,
                    allowedSourceStatuses = listOf(RecordingStatus.TRANSCRIBING),
                    errorMessage = null,
                )
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

                val destinationStatus =
                    persistTranscriptionResultLocked(
                        transcript = transcript,
                        timings = timings,
                        enhancementIntent = enhancementIntent,
                        enhancementExecutionToken = enhancementExecutionToken,
                    )
                recordingDao.updateStatusWithTranscriptionToken(
                    id = transcript.recordingId,
                    status = destinationStatus,
                    errorMessage = null,
                    executionToken = null,
                    allowedCurrentStatuses = listOf(RecordingStatus.TRANSCRIBING),
                    expectedExecutionToken = expectedExecutionToken,
                )
                true
            }

        /**
         * Shared tail of both transcription commits: merge and persist the transcript row,
         * replace the timing rows, and stage or clear the enhancement snapshot. Returns the
         * status the recording should transition to. Must run inside the caller's transaction.
         */
        private suspend fun persistTranscriptionResultLocked(
            transcript: Transcript,
            timings: List<TranscriptTiming>,
            enhancementIntent: RecordingEnhancementIntent?,
            enhancementExecutionToken: String?,
        ): RecordingStatus {
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

            return if (enhancementIntent?.hasRequestedWork == true) {
                enhancementSnapshotDao.upsert(
                    enhancementIntent.toSnapshotEntity(
                        recordingId = transcript.recordingId,
                        transcript = mergedTranscript,
                        enhancementExecutionToken = enhancementExecutionToken,
                        createdAt = now,
                    ),
                )
                RecordingStatus.PENDING_ENHANCEMENT
            } else {
                enhancementSnapshotDao.deleteByRecordingId(transcript.recordingId)
                RecordingStatus.COMPLETED
            }
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
                recordingDao.rearmTerminalNotification(recordingId)
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

        suspend fun reparkEnhancementExecution(
            recordingId: UUID,
            executionToken: String,
            errorMessage: String,
            partialResult: RecordingEnhancementResult? = null,
            sourceTranscriptRevision: String? = null,
            sourceTitle: String? = null,
        ): Boolean =
            database.withTransaction {
                val snapshot = enhancementSnapshotDao.getSnapshot(recordingId) ?: return@withTransaction false
                if (snapshot.activeEnhancementExecutionToken != executionToken) {
                    return@withTransaction false
                }
                val transition =
                    transitionRecordingStatusLocked(
                        id = recordingId,
                        destinationStatus = RecordingStatus.PENDING_ENHANCEMENT,
                        allowedSourceStatuses = listOf(RecordingStatus.ENHANCING),
                        errorMessage = errorMessage,
                    )
                if (transition != RecordingStatusTransitionResult.TransitionApplied) {
                    return@withTransaction false
                }
                // Subwork output that already succeeded this attempt (an on-device transform
                // can take minutes) is persisted before the retry so the next attempt resumes
                // instead of regenerating it. Same transcript/title guards as the final commit.
                var reparked = snapshot
                if (partialResult != null) {
                    val now = Date()
                    val transcript = transcriptDao.getTranscript(recordingId)
                    val transcriptCurrent =
                        transcript != null &&
                            (sourceTranscriptRevision == null || transcript.sourceRevision() == sourceTranscriptRevision)
                    if (transcriptCurrent && (partialResult.processedText != null || partialResult.summary != null)) {
                        transcriptDao.insert(
                            transcript!!.copy(
                                processedText = partialResult.processedText ?: transcript.processedText,
                                processingMode = partialResult.processingMode ?: transcript.processingMode,
                                summary = partialResult.summary ?: transcript.summary,
                                updatedAt = now,
                            ),
                        )
                    }
                    partialResult.title?.let { title ->
                        if (sourceTitle == null) {
                            recordingDao.updateTitle(recordingId, title)
                        } else {
                            recordingDao.updateTitleIfCurrent(recordingId, title, expectedTitle = sourceTitle)
                        }
                    }
                    // Nothing transcript-derived was persisted when the transcript moved on, so
                    // those subworks keep their PENDING/FAILED status and the retry re-runs them.
                    val appliedResult =
                        if (transcriptCurrent) {
                            partialResult
                        } else {
                            partialResult.copy(
                                processingModeStatus = null,
                                processingModeError = null,
                                summaryStatus = null,
                                summaryError = null,
                            )
                        }
                    reparked = snapshot.applyResult(appliedResult, now)
                }
                enhancementSnapshotDao.upsert(reparked.copy(lastErrorMessage = errorMessage))
                true
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
            sourceTitle: String,
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
                sourceTitle = sourceTitle,
            )

        private suspend fun completeEnhancementLocked(
            recordingId: UUID,
            result: RecordingEnhancementResult,
            snapshotGuard: (RecordingEnhancementSnapshotEntity) -> Boolean,
            transcriptGuard: (Transcript) -> Boolean,
            sourceTitle: String? = null,
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
                    // The generated title lands only if the title is still what the enhancement
                    // started from: a rename made while ENHANCING is a deliberate user choice
                    // and must never be silently overwritten (it isn't stored anywhere else).
                    if (sourceTitle == null) {
                        recordingDao.updateTitle(recordingId, title)
                    } else {
                        recordingDao.updateTitleIfCurrent(recordingId, title, expectedTitle = sourceTitle)
                    }
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
            database.withTransaction {
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
                // Same-status "transitions" are allowed for the pending destinations so queue
                // recovery can stamp or clear recovery markers on an already-pending row
                // (markPendingForQueueRecovery / clearPendingError).
                RecordingStatus.PENDING_TRANSCRIPTION ->
                    listOf(
                        RecordingStatus.RECORDING,
                        RecordingStatus.PENDING_TRANSCRIPTION,
                        RecordingStatus.TRANSCRIBING,
                        RecordingStatus.FAILED,
                    )
                RecordingStatus.TRANSCRIBING -> listOf(RecordingStatus.PENDING_TRANSCRIPTION)
                RecordingStatus.PENDING_ENHANCEMENT ->
                    listOf(
                        RecordingStatus.TRANSCRIBING,
                        RecordingStatus.PENDING_ENHANCEMENT,
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
                // The deliberate manual state is only entered through
                // markAwaitingManualTranscription/resolveCancelledEnhancement, which pin
                // their own allowed sources; generic status updates never produce it.
                RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION -> emptyList()
            }

        private fun mergePipelineTranscript(
            transcript: Transcript,
            existing: Transcript?,
            clearManualCorrection: Boolean,
        ): Transcript {
            if (existing == null) return transcript
            // A pipeline write is an update of the existing row, not a new transcript: keep
            // the row identity and the LLM summary (re-transcription regenerates it only
            // when the user asked for one) so a REPLACE insert cannot silently drop them.
            return transcript.copy(
                id = existing.id,
                createdAt = existing.createdAt,
                summary = transcript.summary ?: existing.summary,
                manualCorrectionText = if (clearManualCorrection) null else existing.manualCorrectionText,
                manualCorrectionSourceText = if (clearManualCorrection) null else existing.manualCorrectionSourceText,
            )
        }

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
