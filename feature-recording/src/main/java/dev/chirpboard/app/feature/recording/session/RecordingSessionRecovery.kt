package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.feature.recording.util.probeDurationMs
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
import dev.chirpboard.app.feature.recording.service.RecordingSegmentFinalize
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
        private val sessionJournal: RecordingSessionJournal,
        private val recordingRepository: RecordingRepository,
        private val transcriptionRecovery: TranscriptionRecovery,
        private val fileValidator: RecordingFileValidator,
        private val segmentFinalize: RecordingSegmentFinalize,
        private val capturePaths: RecordingCapturePaths,
        private val sessionReconciler: RecordingSessionReconciler,
        private val recordingStateManager: RecordingStateManager,
        private val protectedPathsStore: RecordingRecoveryProtectedPathsStore,
    ) {
        suspend fun scanForRecoverableSessions(): List<RecoverableRecordingSession> =
            withContext(Dispatchers.IO) {
                sessionReconciler.reconcileCompletedSessions()
                sessionJournal.loadRecoverableSessions().mapNotNull { entry ->
                    if (isLiveSession(entry)) {
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
            withContext(Dispatchers.IO) {
                val entry = sessionJournal.findBySessionId(sessionId)
                    ?: return@withContext SessionRecoveryResult.Failed("Session not found")

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
            withContext(Dispatchers.IO) {
                val entry = sessionJournal.findBySessionId(sessionId)
                entry?.recordingId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
                entry?.let { deleteSessionArtifacts(it) }
                sessionJournal.markFinalized(sessionId)
                SessionRecoveryResult.Discarded
            }

        suspend fun keepSession(sessionId: UUID): SessionRecoveryResult =
            withContext(Dispatchers.IO) {
                val entry = sessionJournal.findBySessionId(sessionId)
                if (entry != null) {
                    protectedPathsStore.protect(sessionJournal.referencedPathsFor(entry))
                    entry.recordingId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
                    sessionJournal.markFinalized(sessionId)
                }
                SessionRecoveryResult.Kept
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
        }
    }
