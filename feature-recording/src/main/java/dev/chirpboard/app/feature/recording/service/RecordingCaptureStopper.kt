package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed class CaptureStopHandoffResult {
    data class Completed(val finalizedFile: File?) : CaptureStopHandoffResult()
    data class TimedOut(val fallbackFile: File?) : CaptureStopHandoffResult()
    data class Failed(val fallbackFile: File?, val cause: Throwable) : CaptureStopHandoffResult()
    object StaleGeneration : CaptureStopHandoffResult()
}

internal object RecordingCaptureStopper {
    suspend fun stopForHandoff(
        segmentTransitionMutex: Mutex,
        stopGeneration: AtomicInteger,
        generation: Int,
        sessionId: UUID?,
        sessionJournal: RecordingSessionJournal,
        captureProvider: () -> GaplessSegmentCaptureEngine?,
        activeFileProvider: () -> File?,
        timeoutMs: Long,
    ): CaptureStopHandoffResult =
        segmentTransitionMutex.withLock {
            if (generation != stopGeneration.get()) {
                return@withLock CaptureStopHandoffResult.StaleGeneration
            }

            val capture = captureProvider()
            val fallbackFile = activeFileProvider()
            val result =
                withContext(Dispatchers.IO) {
                    capture?.stopAndFinalizeBounded(timeoutMs)
                        ?: CaptureStopResult.Completed(fallbackFile)
                }

            if (generation != stopGeneration.get()) {
                return@withLock CaptureStopHandoffResult.StaleGeneration
            }

            val finalizedFile =
                when (result) {
                    is CaptureStopResult.Completed -> result.finalizedFile ?: fallbackFile
                    is CaptureStopResult.TimedOut -> fallbackFile
                    is CaptureStopResult.Failed -> fallbackFile
                }

            if (sessionId != null && finalizedFile != null) {
                sessionJournal.commitStoppedSegment(
                    sessionId = sessionId,
                    completedSegmentPath = finalizedFile.absolutePath,
                    fileBytes = finalizedFile.takeIf(File::exists)?.length() ?: 0L,
                )
            }

            when (result) {
                is CaptureStopResult.Completed -> CaptureStopHandoffResult.Completed(finalizedFile)
                is CaptureStopResult.TimedOut -> CaptureStopHandoffResult.TimedOut(finalizedFile)
                is CaptureStopResult.Failed -> CaptureStopHandoffResult.Failed(finalizedFile, result.cause)
            }
        }
}
