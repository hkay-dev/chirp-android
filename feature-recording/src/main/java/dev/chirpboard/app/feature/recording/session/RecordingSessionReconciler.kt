package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import java.io.File
import java.util.UUID
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
                        if (recording != null && rowReferencesCaptureArtifacts(recording.audioPath, entry.sessionId)) {
                            // The concat fallback can finalize the row onto a segment file
                            // inside the capture dir (and the journal can still be STOPPING
                            // between the row commit and markFinalized); deleting the
                            // artifacts here would orphan the row's only audio.
                            return@forEach
                        }
                        capturePaths.deleteCaptureArtifacts(entry.sessionId)
                    }
                }
            }

        private fun rowReferencesCaptureArtifacts(
            audioPath: String,
            sessionId: UUID,
        ): Boolean {
            if (audioPath.isBlank()) {
                return false
            }
            val captureDir = capturePaths.captureDir(sessionId)
            val canonicalDir =
                runCatching { captureDir.canonicalPath }.getOrDefault(captureDir.absolutePath)
            val canonicalAudio =
                runCatching { File(audioPath).canonicalPath }.getOrDefault(File(audioPath).absolutePath)
            return canonicalAudio.startsWith(canonicalDir + File.separator)
        }
    }
