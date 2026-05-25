package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cleans up session journal entries that outlived the recording they tracked.
 *
 * This prevents completed recordings from continuing to appear as recoverable
 * when the journal file was not deleted during stop.
 */
@Singleton
class RecordingSessionReconciler
    @Inject
    constructor(
        private val sessionJournal: RecordingSessionJournal,
        private val recordingRepository: RecordingRepository,
        private val capturePaths: RecordingCapturePaths,
    ) {
        suspend fun reconcileCompletedSessions() =
            withContext(Dispatchers.IO) {
                sessionJournal.loadActiveSessions().forEach { entry ->
                    val recordingId = entry.recordingId ?: return@forEach
                    val recording = recordingRepository.getRecording(recordingId)
                    if (recording == null || recording.status != RecordingStatus.RECORDING) {
                        sessionJournal.markFinalized(entry.sessionId)
                        capturePaths.deleteCaptureArtifacts(entry.sessionId)
                    }
                }
            }
    }
