package dev.chirpboard.app.feature.recording.service

import android.media.MediaMetadataRetriever
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingStopOrchestrator
    @Inject
    constructor(
        private val recordingRepository: RecordingRepository,
        private val transcriptionRecovery: TranscriptionRecovery,
        private val fileValidator: RecordingFileValidator,
        private val sessionJournal: RecordingSessionJournal,
        private val segmentFinalize: RecordingSegmentFinalize,
    ) {
        suspend fun persistAndQueueRecording(
            snapshot: StopSnapshot,
            sessionId: UUID? = null,
        ): StopPersistenceResult {
            val exportFile =
                withContext(Dispatchers.IO) {
                    segmentFinalize.materializeExportFile(sessionId, snapshot.audioFilePath)
                } ?: run {
                    val fallbackPath = snapshot.audioFilePath
                    if (fallbackPath == null) return StopPersistenceResult.NoAudioFile
                    File(fallbackPath).takeIf { it.exists() }
                }

            if (exportFile == null || !exportFile.exists()) {
                return StopPersistenceResult.NoAudioFile
            }

            val validation = fileValidator.validateForStop(exportFile)
            if (!validation.isPlayable) {
                return StopPersistenceResult.PersistenceFailed(
                    validation.failureReason ?: "Recording file validation failed",
                )
            }

            return withContext(Dispatchers.IO) {
                val containerDurationMs = probeDurationMs(exportFile)
                val durationMs =
                    when {
                        containerDurationMs > 0L -> containerDurationMs
                        snapshot.durationMs > 0L -> snapshot.durationMs
                        else -> 0L
                    }
                val title = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(snapshot.stoppedAtEpochMs))
                val source =
                    when (snapshot.origin) {
                        RecordingOrigin.APP -> RecordingSource.APP
                        RecordingOrigin.KEYBOARD -> RecordingSource.KEYBOARD
                        RecordingOrigin.WIDGET -> RecordingSource.WIDGET
                    }

                val recording =
                    try {
                        val inProgressId = snapshot.recordingId
                        if (inProgressId != null) {
                            recordingRepository.finalizeInProgressRecording(
                                recordingId = inProgressId,
                                durationMs = durationMs,
                                title = title,
                            ) ?: recordingRepository.createRecording(
                                title = title,
                                audioPath = exportFile.absolutePath,
                                source = source,
                                profileId = snapshot.profileId,
                                durationMs = durationMs,
                            )
                        } else {
                            recordingRepository.createRecording(
                                title = title,
                                audioPath = exportFile.absolutePath,
                                source = source,
                                profileId = snapshot.profileId,
                                durationMs = durationMs,
                            )
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        return@withContext StopPersistenceResult.PersistenceFailed(
                            "Failed to save recording: ${e.message}",
                            e,
                        )
                    }

                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.PERSISTENCE_SAVE,
                    outcome = ReliabilityOutcome.SUCCESS,
                    correlationId = snapshot.correlationId,
                    recordingId = recording.id,
                    reasonCode = "recording_saved",
                )

                sessionId?.let { sessionJournal.markFinalized(it) }

                try {
                    transcriptionRecovery.enqueue(recording.id, snapshot.correlationId)
                    StopPersistenceResult.SavedAndQueued(recording.id)
                } catch (enqueueError: Exception) {
                    if (enqueueError is kotlinx.coroutines.CancellationException) throw enqueueError
                    val reason = "Queue handoff failed during stop. Will retry automatically on startup."
                    transcriptionRecovery.markPendingForQueueRecovery(recording.id, reason, enqueueError)
                    StopPersistenceResult.SavedPendingRecovery(recording.id, reason, enqueueError)
                }
            }
        }

        private fun probeDurationMs(file: File): Long =
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                }
            }.getOrNull()?.coerceAtLeast(0L) ?: 0L
    }
