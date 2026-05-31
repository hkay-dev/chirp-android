package dev.chirpboard.app.feature.recording.service

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.every
import io.mockk.mockk

class RecordingCaptureStopperTest {
    @Test
    fun stopTimeout_returnsWithinBudgetAndKeepsJournalHandle() =
        runTest {
            val root = createTempDir("capture-stop-timeout")
            val context =
                mockk<Context> {
                    every { filesDir } returns root
                }
            val journal = RecordingSessionJournal(context)
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val activeFile = File(root, "recordings/active.m4a").apply {
                parentFile?.mkdirs()
                writeText("recoverable")
            }
            journal.createSession(
                sessionId = sessionId,
                audioPath = activeFile.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr",
            )

            val captureStarted = CountDownLatch(1)
            val capture =
                object : NoOpGaplessCapture() {
                    override fun stopAndFinalize(): File? {
                        captureStarted.countDown()
                        Thread.sleep(TimeUnit.SECONDS.toMillis(10))
                        return activeFile
                    }
                }

            val startedAt = System.nanoTime()
            val result =
                RecordingCaptureStopper.stopForHandoff(
                    segmentTransitionMutex = Mutex(),
                    stopGeneration = AtomicInteger(1),
                    generation = 1,
                    sessionId = sessionId,
                    sessionJournal = journal,
                    captureProvider = { capture },
                    activeFileProvider = { activeFile },
                    timeoutMs = 50L,
                )
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(captureStarted.await(1, TimeUnit.SECONDS))
            assertTrue(result is CaptureStopHandoffResult.TimedOut)
            assertTrue(elapsedMs < 1_000L)
            val entry = journal.findBySessionId(sessionId)
            assertEquals(recordingId, entry?.recordingId)
            assertEquals(listOf(activeFile.absolutePath), entry?.segmentPaths)
        }

    @Test
    fun stopWaitsForRotationBoundaryAndCommitsTailOnce() =
        runTest {
            val root = createTempDir("capture-stop-rotation")
            val context =
                mockk<Context> {
                    every { filesDir } returns root
                }
            val journal = RecordingSessionJournal(context)
            val sessionId = UUID.randomUUID()
            val firstSegment = File(root, "recordings/seg-0.m4a").apply {
                parentFile?.mkdirs()
                writeText("first")
            }
            val tailSegment = File(root, "recordings/seg-1.m4a").apply {
                parentFile?.mkdirs()
                writeText("tail")
            }
            var currentFile = firstSegment
            journal.createSession(
                sessionId = sessionId,
                audioPath = firstSegment.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = UUID.randomUUID(),
                correlationId = "corr",
            )

            val mutex = Mutex(locked = true)
            val stop =
                async {
                    RecordingCaptureStopper.stopForHandoff(
                        segmentTransitionMutex = mutex,
                        stopGeneration = AtomicInteger(1),
                        generation = 1,
                        sessionId = sessionId,
                        sessionJournal = journal,
                        captureProvider = { NoOpGaplessCapture(finalizedFile = currentFile) },
                        activeFileProvider = { currentFile },
                        timeoutMs = 1_000L,
                    )
                }

            journal.appendCompletedSegment(
                sessionId = sessionId,
                completedSegmentPath = firstSegment.absolutePath,
                nextSegmentPath = tailSegment.absolutePath,
                fileBytes = firstSegment.length(),
            )
            currentFile = tailSegment
            mutex.unlock()
            stop.await()

            assertEquals(
                listOf(firstSegment.absolutePath, tailSegment.absolutePath),
                journal.findBySessionId(sessionId)?.segmentPaths,
            )
        }
}

private open class NoOpGaplessCapture(
    private val finalizedFile: File? = null,
) : GaplessSegmentCaptureEngine {
    override suspend fun start(segmentFile: File) = Unit

    override fun rotateSegment(nextSegmentFile: File): SegmentRotationResult =
        SegmentRotationResult.Failed("unused")

    override fun cancelPendingRotation() = Unit

    override fun pauseAndFinalizeSegment(): File? = finalizedFile

    override suspend fun resume(nextSegmentFile: File) = Unit

    override fun stopAndFinalize(): File? = finalizedFile

    override fun releaseWithoutSave() = Unit

    override val maxAmplitude: Int = 0
}
