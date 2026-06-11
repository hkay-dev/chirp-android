package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.feature.recording.util.probeDurationMs
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import dev.chirpboard.app.feature.recording.session.validation.RecordingValidationLevel
import dev.chirpboard.app.feature.recording.service.RecordingFinalizeWorkRequest
import dev.chirpboard.app.feature.recording.service.RecordingSegmentFinalize
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class RecoverableRecordingSession(
    val sessionId: UUID,
    val audioPath: String,
    val fileSizeBytes: Long,
    val startedAtEpochMs: Long,
    val origin: RecordingOrigin,
    val profileId: UUID?,
    val recoverableDurationMs: Long,
    val estimatedLostDurationMs: Long,
    val hasPotentialLoss: Boolean,
) {
    fun estimatedLostMinutes(): Int =
        TimeUnit.MILLISECONDS.toMinutes(estimatedLostDurationMs.coerceAtLeast(0L))
            .toInt()
            .coerceAtLeast(1)
}

sealed class SessionRecoveryResult {
    data class Recovered(
        val recordingId: UUID,
        val estimatedLostMinutes: Int? = null,
    ) : SessionRecoveryResult()

    data class Failed(val message: String) : SessionRecoveryResult()

    object Discarded : SessionRecoveryResult()

    object Kept : SessionRecoveryResult()
}

@Singleton
class RecordingSessionRecovery
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sessionJournal: RecordingSessionJournal,
        private val recordingRepository: RecordingRepository,
        private val transcriptionRecovery: TranscriptionRecovery,
        private val fileValidator: RecordingFileValidator,
        private val segmentFinalize: RecordingSegmentFinalize,
        private val capturePaths: RecordingCapturePaths,
        private val sessionReconciler: RecordingSessionReconciler,
        private val recordingStateManager: RecordingStateManager,
        private val protectedPathsStore: RecordingRecoveryProtectedPathsStore,
        private val ownershipLock: RecordingFinalizeOwnershipLock,
    ) {
        suspend fun scanForRecoverableSessions(): List<RecoverableRecordingSession> =
            withContext(Dispatchers.IO) {
                sessionReconciler.reconcileCompletedSessions()
                sessionJournal.loadRecoverableSessions().mapNotNull { entry ->
                    if (isLiveSession(entry)) {
                        return@mapNotNull null
                    }
                    if (hasUnfinishedFinalizeWork(entry) != false) {
                        // The finalize worker still owns this session (or the work query
                        // failed, leaving ownership unknown); offering it in the recovery
                        // UI would race the worker and double-finalize. A failed query
                        // only hides the session for this scan pass: the journal entry
                        // and its files persist, and the next refresh re-lists it.
                        return@mapNotNull null
                    }
                    val resolved = resolveRecoveryFile(entry)
                    if (resolved == null || resolved.length() < RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES) {
                        null
                    } else {
                        val assessment = SessionRecoveryAssessor.assess(entry)
                        RecoverableRecordingSession(
                            sessionId = entry.sessionId,
                            audioPath = resolved.absolutePath,
                            fileSizeBytes = resolved.length(),
                            startedAtEpochMs = entry.startedAtEpochMs,
                            origin = entry.origin,
                            profileId = entry.profileId,
                            recoverableDurationMs = assessment.recoverableDurationMs,
                            estimatedLostDurationMs = assessment.estimatedLostDurationMs,
                            hasPotentialLoss = assessment.hasPotentialLoss,
                        )
                    }
                }
            }

        suspend fun recoverDurableStoppedSessions(excludingSessionIds: Set<UUID> = emptySet()) {
            val sessions =
                withContext(Dispatchers.IO) {
                    sessionJournal.loadRecoverableSessions().filter { entry ->
                        entry.sessionId !in excludingSessionIds &&
                            !isLiveSession(entry) &&
                            (entry.finalAudioPath
                                ?.let(::File)
                                ?.let { file -> file.exists() && file.length() >= RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES }
                                == true)
                    }
                }

            sessions.forEach { entry ->
                when (val result = recoverSession(entry.sessionId)) {
                    is SessionRecoveryResult.Recovered,
                    SessionRecoveryResult.Kept,
                    SessionRecoveryResult.Discarded,
                    -> Unit
                    is SessionRecoveryResult.Failed ->
                        Log.w(TAG, "Durable stopped session recovery failed for ${entry.sessionId}: ${result.message}")
                }
            }
        }

        suspend fun recoverSession(sessionId: UUID): SessionRecoveryResult =
            // Hold the ownership lock across check-and-act so the startup reconciler
            // cannot enqueue finalize work for this session between the check below
            // and the file/database mutations that follow.
            ownershipLock.withLock { recoverSessionLocked(sessionId) }

        private suspend fun recoverSessionLocked(sessionId: UUID): SessionRecoveryResult =
            withContext(Dispatchers.IO) {
                val entry = sessionJournal.findBySessionId(sessionId)
                    ?: return@withContext SessionRecoveryResult.Failed("Session not found")

                when (hasUnfinishedFinalizeWork(entry)) {
                    // The finalize worker still owns this session (e.g. a stale recovery
                    // card raced the worker); recovering here would double-finalize it.
                    true -> return@withContext SessionRecoveryResult.Failed(FINALIZE_IN_PROGRESS_MESSAGE)
                    // Unknown ownership: fail closed instead of racing a possible worker.
                    null -> return@withContext SessionRecoveryResult.Failed(FINALIZE_STATE_UNKNOWN_MESSAGE)
                    false -> Unit
                }

                val assessment = SessionRecoveryAssessor.assess(entry)

                val exportFile =
                    if (entry.usesSegmentCapture()) {
                        segmentFinalize.materializeExportFile(sessionId, entry.audioPath)
                    } else {
                        resolveRecoveryFile(entry)
                    }

                if (exportFile == null || !exportFile.exists()) {
                    sessionJournal.markAbandoned(sessionId)
                    return@withContext SessionRecoveryResult.Failed("Audio file missing")
                }

                val validation = fileValidator.validateForRecovery(exportFile)
                if (validation.level == RecordingValidationLevel.INVALID) {
                    sessionJournal.markAbandoned(sessionId)
                    return@withContext SessionRecoveryResult.Failed(
                        validation.failureReason ?: "Recording file could not be validated",
                    )
                }
                if (RecordingOutputFormat.fromFile(exportFile) == RecordingOutputFormat.WAV) {
                    // A crash can leave a zeroed or stale-size WAV header; repair it from the
                    // actual PCM payload before treating the file as playable.
                    WavFileWriter.repairHeaderIfNeeded(exportFile)
                }

                val durationMs = probeDurationMs(exportFile)
                val correlationId = entry.correlationId ?: ReliabilityEventLogger.newCorrelationId("recover")
                val title =
                    SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(entry.startedAtEpochMs)) +
                        " (recovered)"

                val source =
                    when (entry.origin) {
                        RecordingOrigin.APP -> RecordingSource.APP
                        RecordingOrigin.KEYBOARD -> RecordingSource.KEYBOARD
                        RecordingOrigin.WIDGET -> RecordingSource.WIDGET
                    }

                var linkedRecordingExists = false
                entry.recordingId?.let { linkedRecordingId ->
                    when (val existing = recordingRepository.getRecording(linkedRecordingId)) {
                        null -> Unit
                        else ->
                            if (existing.status != RecordingStatus.RECORDING) {
                                sessionJournal.markFinalized(sessionId)
                                return@withContext SessionRecoveryResult.Recovered(
                                    recordingId = existing.id,
                                )
                            } else {
                                linkedRecordingExists = true
                            }
                    }
                }

                return@withContext try {
                    val recording =
                        if (entry.recordingId != null && linkedRecordingExists) {
                            recordingRepository.finalizeInProgressRecording(
                                recordingId = entry.recordingId,
                                durationMs = durationMs,
                                title = title,
                                audioPath = exportFile.absolutePath,
                            )
                        } else {
                            recordingRepository.createRecording(
                                title = title,
                                audioPath = exportFile.absolutePath,
                                source = source,
                                profileId = entry.profileId,
                                durationMs = durationMs,
                            )
                        } ?: return@withContext SessionRecoveryResult.Failed(
                            "Linked recording could not be finalized",
                        )

                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.PERSISTENCE_SAVE,
                        outcome = ReliabilityOutcome.SUCCESS,
                        correlationId = correlationId,
                        recordingId = recording.id,
                        reasonCode = "session_recovered",
                    )

                    try {
                        transcriptionRecovery.enqueue(recording.id, correlationId)
                    } catch (enqueueError: kotlinx.coroutines.CancellationException) {
                        throw enqueueError
                    } catch (enqueueError: Exception) {
                        transcriptionRecovery.markPendingForQueueRecovery(
                            recording.id,
                            "Queue handoff failed during session recovery.",
                            enqueueError,
                        )
                    }

                    sessionJournal.markFinalized(sessionId)
                    SessionRecoveryResult.Recovered(
                        recordingId = recording.id,
                        estimatedLostMinutes =
                            assessment.estimatedLostDurationMs
                                .takeIf { assessment.hasPotentialLoss }
                                ?.let { assessment.lossSummaryMinutes() },
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to recover session $sessionId", e)
                    SessionRecoveryResult.Failed("Recovery failed: ${e.message}")
                }
            }

        suspend fun discardSession(sessionId: UUID): SessionRecoveryResult =
            ownershipLock.withLock { discardSessionLocked(sessionId) }

        private suspend fun discardSessionLocked(sessionId: UUID): SessionRecoveryResult =
            withContext(Dispatchers.IO) {
                val entry = sessionJournal.findBySessionId(sessionId)
                if (entry != null) {
                    when (hasUnfinishedFinalizeWork(entry)) {
                        // The finalize worker still owns this session; deleting its artifacts
                        // now would race the worker's move/validate of the same files.
                        true -> return@withContext SessionRecoveryResult.Failed(FINALIZE_IN_PROGRESS_MESSAGE)
                        // Unknown ownership must fail closed on this destructive path:
                        // deleting files a live worker may be moving loses audio.
                        null -> return@withContext SessionRecoveryResult.Failed(FINALIZE_STATE_UNKNOWN_MESSAGE)
                        false -> Unit
                    }
                }
                entry?.recordingId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
                entry?.let { deleteSessionArtifacts(it) }
                sessionJournal.markFinalized(sessionId)
                SessionRecoveryResult.Discarded
            }

        suspend fun keepSession(sessionId: UUID): SessionRecoveryResult =
            ownershipLock.withLock { keepSessionLocked(sessionId) }

        private suspend fun keepSessionLocked(sessionId: UUID): SessionRecoveryResult =
            withContext(Dispatchers.IO) {
                val entry = sessionJournal.findBySessionId(sessionId)
                if (entry != null) {
                    when (hasUnfinishedFinalizeWork(entry)) {
                        // Same race as recover/discard: the finalize worker still owns the
                        // recording row and journal entry for this session.
                        true -> return@withContext SessionRecoveryResult.Failed(FINALIZE_IN_PROGRESS_MESSAGE)
                        null -> return@withContext SessionRecoveryResult.Failed(FINALIZE_STATE_UNKNOWN_MESSAGE)
                        false -> Unit
                    }
                    protectedPathsStore.protect(sessionJournal.referencedPathsFor(entry))
                    entry.recordingId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
                    sessionJournal.markFinalized(sessionId)
                }
                SessionRecoveryResult.Kept
            }

        /**
         * Whether the finalize worker still owns this session. Returns null when the
         * WorkManager query fails: callers must fail closed (skip the entry for this
         * scan pass, or refuse the action) instead of assuming no work exists and
         * racing a possibly live worker.
         */
        private suspend fun hasUnfinishedFinalizeWork(entry: RecordingSessionEntry): Boolean? {
            val recordingId = entry.recordingId ?: return false
            return try {
                RecordingFinalizeWorkRequest.hasUnfinishedWork(context, recordingId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not query finalize work for ${entry.sessionId}", e)
                null
            }
        }

        private fun isLiveSession(entry: RecordingSessionEntry): Boolean {
            val state = recordingStateManager.state.value
            if (!state.isActive) {
                return false
            }
            val activeRecordingId = state.activeRecordingId ?: return false
            return entry.recordingId == activeRecordingId
        }

        private fun resolveRecoveryFile(entry: RecordingSessionEntry): File? {
            if (entry.usesSegmentCapture()) {
                val export = File(entry.exportAudioPath())
                if (export.exists() && fileValidator.validateForStop(export).isPlayable) {
                    return export
                }
                val segments = entry.orderedSegmentFiles()
                if (segments.isNotEmpty()) {
                    return segments.maxByOrNull { it.length() }
                }
                if (export.exists() && fileValidator.validateForRecovery(export).isRecoverableStub) {
                    // Pre-fix app versions could delete segments while leaving an export
                    // with a stale/zeroed header; with no segments left, that export's
                    // payload is the only remaining audio and recovery can repair it.
                    return export
                }
            }

            val primary = File(entry.audioPath)
            if (primary.exists() && fileValidator.validateForStop(primary).isPlayable) {
                return primary
            }
            val checkpointPath = entry.checkpointPath ?: RecordingFileValidator.checkpointPathFor(entry.audioPath)
            val checkpoint = File(checkpointPath)
            if (checkpoint.exists() && fileValidator.validateForRecovery(checkpoint).isRecoverableStub) {
                return checkpoint
            }
            if (primary.exists() && fileValidator.validateForRecovery(primary).isRecoverableStub) {
                return primary
            }
            val recovery = File(RecordingFileValidator.recoveryPathFor(entry.audioPath))
            if (recovery.exists()) return recovery
            return primary.takeIf { it.exists() }
        }

        private fun deleteSessionArtifacts(entry: RecordingSessionEntry) {
            capturePaths.deleteCaptureArtifacts(entry.sessionId)
            File(entry.audioPath).takeIf(File::exists)?.delete()
            entry.finalAudioPath?.let { File(it).takeIf(File::exists)?.delete() }
            entry.segmentPaths.forEach { path -> File(path).takeIf(File::exists)?.delete() }
            entry.checkpointPath?.let { File(it).takeIf(File::exists)?.delete() }
            File(RecordingFileValidator.checkpointPathFor(entry.audioPath)).takeIf(File::exists)?.delete()
            File(RecordingFileValidator.recoveryPathFor(entry.audioPath)).takeIf(File::exists)?.delete()
            entry.finalAudioPath?.let { finalPath ->
                File(RecordingFileValidator.checkpointPathFor(finalPath)).takeIf(File::exists)?.delete()
                File(RecordingFileValidator.recoveryPathFor(finalPath)).takeIf(File::exists)?.delete()
            }
        }

        companion object {
            private const val TAG = "RecordingSessionRecovery"
            internal const val FINALIZE_IN_PROGRESS_MESSAGE =
                "Recording is still being finalized. Try again in a moment."
            internal const val FINALIZE_STATE_UNKNOWN_MESSAGE =
                "Couldn't confirm this recording finished saving. Try again in a moment."
        }
    }
