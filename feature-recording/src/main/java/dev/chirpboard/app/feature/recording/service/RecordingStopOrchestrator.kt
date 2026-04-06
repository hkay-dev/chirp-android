package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.transcription.TranscriptionQueueManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingStopOrchestrator
    @Inject
    constructor(
        private val recordingRepository: RecordingRepository,
        private val transcriptionQueueManager: TranscriptionQueueManager,
    ) {
        suspend fun persistAndQueueRecording(snapshot: StopSnapshot): StopPersistenceResult {
            val audioPath = snapshot.audioFilePath ?: return StopPersistenceResult.NoAudioFile
            val file = File(audioPath)
            if (!file.exists()) {
                return StopPersistenceResult.NoAudioFile
            }

            return withContext(Dispatchers.IO) {
                val title = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(snapshot.stoppedAtEpochMs))
                val source =
                    when (snapshot.origin) {
                        RecordingOrigin.APP -> RecordingSource.APP
                        RecordingOrigin.KEYBOARD -> RecordingSource.KEYBOARD
                        RecordingOrigin.WIDGET -> RecordingSource.WIDGET
                    }

                val recording =
                    recordingRepository.createRecording(
                        title = title,
                        audioPath = audioPath,
                        source = source,
                        profileId = snapshot.profileId,
                        durationMs = snapshot.durationMs,
                    )

                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.PERSISTENCE_SAVE,
                    outcome = ReliabilityOutcome.SUCCESS,
                    correlationId = snapshot.correlationId,
                    recordingId = recording.id,
                    reasonCode = "recording_saved",
                )

                try {
                    transcriptionQueueManager.enqueue(recording.id, snapshot.correlationId)
                    StopPersistenceResult.SavedAndQueued(recording.id)
                } catch (enqueueError: Exception) {
                    if (enqueueError is kotlinx.coroutines.CancellationException) throw enqueueError
                    val reason = "Queue handoff failed during stop. Will retry automatically on startup."
                    transcriptionQueueManager.markPendingForQueueRecovery(recording.id, reason, enqueueError)
                    StopPersistenceResult.SavedPendingRecovery(recording.id, reason, enqueueError)
                }
            }
        }
    }
