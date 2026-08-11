package dev.chirpboard.app.feature.recording.session

import android.util.Log
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.feature.recording.service.GaplessSegmentCaptureEngine
import dev.chirpboard.app.feature.recording.service.SegmentRotationResult
import dev.chirpboard.app.feature.recording.service.StopRequestGate
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SegmentRotationOutcome(
    val nextSegmentFile: File?,
)

@Singleton
class RecordingSegmentRotator
    @Inject
    constructor(
        private val sessionJournal: RecordingSessionJournal,
        private val capturePaths: RecordingCapturePaths,
        private val fileValidator: RecordingFileValidator,
    ) {
        suspend fun rotateIfNeeded(
            recordingStateManager: RecordingStateManager,
            stopRequestGate: StopRequestGate,
            segmentTransitionMutex: Mutex,
            sessionId: UUID?,
            segmentCapture: GaplessSegmentCaptureEngine?,
            currentRecordingFile: File?,
            correlationId: String?,
        ): SegmentRotationOutcome? {
            if (recordingStateManager.state.value !is RecordingState.Recording) return null

            return segmentTransitionMutex.withLock {
                if (stopRequestGate.isInProgress()) return@withLock null
                if (recordingStateManager.state.value !is RecordingState.Recording) return@withLock null

                val activeSessionId = sessionId ?: return@withLock null
                val entry = sessionJournal.findBySessionId(activeSessionId) ?: return@withLock null
                val capture = segmentCapture ?: return@withLock null
                val completedFile = currentRecordingFile ?: return@withLock null

                val nextIndex = entry.segmentPaths.size + 1
                val nextSegment = capturePaths.durableSegmentFile(activeSessionId, nextIndex)
                val rotationLog =
                    ReliabilityEventLogger.scoped(
                        stage = ReliabilityStage.RECORDING_START,
                        correlationId = correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    )

                val rotationResult =
                    withContext(Dispatchers.IO) {
                        capture.rotateSegment(nextSegment)
                    }

                if (rotationResult !is SegmentRotationResult.Success) {
                    val reason = (rotationResult as? SegmentRotationResult.Failed)?.reason ?: "unknown"
                    Log.w(TAG, "Gapless segment rotation skipped: $reason")
                    rotationLog.failure("segment_rotation_failed", message = reason)
                    return@withLock null
                }

                // Advisory only: the engine has already switched to nextSegment, so the
                // journal and state MUST record the switch no matter what the completed
                // file looks like. Bailing out here used to leave the journal on the old
                // segment; the next tick then recomputed the same segment index and the
                // engine's rotation into that same file truncated the audio it was
                // actively writing, capping the recording at the first segment forever.
                val completedValidation = fileValidator.validateForRecovery(completedFile)
                if (!completedValidation.isRecoverableStub) {
                    Log.w(
                        TAG,
                        "Gapless segment rotation produced invalid segment: ${completedValidation.failureReason}",
                    )
                    rotationLog.failure("segment_rotation_invalid", message = completedValidation.failureReason)
                }

                sessionJournal.appendCompletedSegment(
                    sessionId = activeSessionId,
                    completedSegmentPath = completedFile.absolutePath,
                    nextSegmentPath = nextSegment.absolutePath,
                    fileBytes = nextSegment.takeIf { it.exists() }?.length() ?: 0L,
                )

                recordingStateManager.rotateSegment(nextSegment.absolutePath)
                rotationLog.success("segment_rotated_gapless", message = "segment=$nextIndex")
                SegmentRotationOutcome(nextSegmentFile = nextSegment)
            }
        }

        companion object {
            private const val TAG = "RecordingSegmentRotator"
        }
    }
