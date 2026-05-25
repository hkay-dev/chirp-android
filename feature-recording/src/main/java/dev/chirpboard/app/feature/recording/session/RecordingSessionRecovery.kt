package dev.chirpboard.app.feature.recording.session

import android.media.MediaMetadataRetriever
import android.util.Log
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.service.RecordingFileValidator
import dev.chirpboard.app.feature.recording.service.RecordingSegmentFinalize
import dev.chirpboard.app.feature.recording.util.useCompat
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
    ) {
        suspend fun scanForRecoverableSessions(): List<RecoverableRecordingSession> =
            withContext(Dispatchers.IO) {
                sessionJournal.loadRecoverableSessions().mapNotNull { entry ->
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
                if (validation.level == dev.chirpboard.app.feature.recording.service.RecordingValidationLevel.INVALID) {
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

                return@withContext try {
                    val recording =
                        entry.recordingId?.let { inProgressId ->
                            recordingRepository.finalizeInProgressRecording(
                                recordingId = inProgressId,
                                durationMs = durationMs,
                                title = title,
                            )
                        } ?: recordingRepository.createRecording(
                            title = title,
                            audioPath = exportFile.absolutePath,
                            source = source,
                            profileId = entry.profileId,
                            durationMs = durationMs,
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
                entry?.recordingId?.let { recordingRepository.deleteInProgressRecording(it) }
                entry?.let { deleteSessionArtifacts(it) }
                sessionJournal.markFinalized(sessionId)
                SessionRecoveryResult.Discarded
            }

        suspend fun keepSession(sessionId: UUID): SessionRecoveryResult =
            withContext(Dispatchers.IO) {
                sessionJournal.markAbandoned(sessionId)
                SessionRecoveryResult.Kept
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

        private fun probeDurationMs(file: File): Long =
            runCatching {
                MediaMetadataRetriever().useCompat { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                }
            }.getOrNull()?.coerceAtLeast(0L) ?: 0L

        companion object {
            private const val TAG = "RecordingSessionRecovery"
        }
    }
