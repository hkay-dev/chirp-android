package dev.chirpboard.app.feature.recording.service

import android.media.AudioRecord
import android.os.Process
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class GaplessWavSegmentCaptureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var inputDeviceSelector: AudioInputDeviceSelector
    private lateinit var audioRecord: AudioRecord

    @Before
    fun setUp() {
        mockkStatic(AudioRecord::class)
        mockkStatic(Process::class)
        every { Process.setThreadPriority(any()) } just runs
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 2048

        audioRecord = mockk(relaxed = true)
        every { audioRecord.state } returns AudioRecord.STATE_INITIALIZED
        every { audioRecord.startRecording() } just runs
        every { audioRecord.stop() } just runs
        every { audioRecord.release() } just runs
        every {
            audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
        } answers {
            val buffer = firstArg<ByteArray>()
            for (index in buffer.indices) {
                buffer[index] = 0x01
            }
            buffer.size
        }

        inputDeviceSelector = mockk()
        coEvery {
            inputDeviceSelector.buildAudioRecord(
                audioSource = any(),
                sampleRate = any(),
                channelConfig = any(),
                audioFormat = any(),
                bufferSize = any(),
            )
        } returns audioRecord
    }

    @After
    fun tearDown() {
        unmockkStatic(AudioRecord::class)
        unmockkStatic(Process::class)
    }

    @Test
    fun rotateSegment_whenNotRunning_returnsFailed() {
        val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
        val nextSegment = File(temporaryFolder.root, "next.wav")

        val result = capture.rotateSegment(nextSegment)

        assertTrue(result is SegmentRotationResult.Failed)
    }

    @Test
    fun rotateSegment_thenStopAndFinalize_writesRecoverableSegments() =
        runTest {
            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val segmentDir = temporaryFolder.newFolder("segments")
            val firstSegment = File(segmentDir, "segment-001.wav")
            val secondSegment = File(segmentDir, "segment-002.wav")

            capture.start(firstSegment)
            Thread.sleep(300)

            val rotation = capture.rotateSegment(secondSegment)
            assertEquals(SegmentRotationResult.Success, rotation)
            assertTrue(firstSegment.length() >= RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES)

            val finalized = capture.stopAndFinalize()
            assertEquals(secondSegment, finalized)
            assertTrue(secondSegment.exists())
        }

    @Test
    fun stopAndFinalize_afterAudioRecordReadError_returnsWithoutHanging() =
        runTest {
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } returns AudioRecord.ERROR_DEAD_OBJECT

            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val segment = File(temporaryFolder.root, "read-error.wav")

            capture.start(segment)
            Thread.sleep(100)

            val finalized = capture.stopAndFinalize()

            assertEquals(segment, finalized)
            assertTrue(segment.exists())
        }

    @Test
    fun stopAndFinalize_whileReadBlocked_returnsQuicklyAndKeepsFinalBuffer() =
        runTest {
            // The first read returns immediately; subsequent reads block until
            // AudioRecord.stop() is called (mirroring READ_BLOCKING on real hardware),
            // then exactly one final partial buffer is delivered.
            val firstReadDone = AtomicBoolean(false)
            val stopSignal = CountDownLatch(1)
            val postStopReads = AtomicInteger(0)
            every { audioRecord.stop() } answers { stopSignal.countDown() }
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } answers {
                val buffer = firstArg<ByteArray>()
                if (!firstReadDone.getAndSet(true)) {
                    buffer.fill(0x01)
                    buffer.size
                } else if (
                    stopSignal.await(6, TimeUnit.SECONDS) &&
                    postStopReads.getAndIncrement() == 0
                ) {
                    buffer.fill(0x02)
                    buffer.size
                } else {
                    0
                }
            }

            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val errors = CopyOnWriteArrayList<GaplessCaptureError>()
            capture.setCaptureErrorListener { errors += it }
            val segment = File(temporaryFolder.root, "blocked-read.wav")

            capture.start(segment)
            Thread.sleep(150)

            val startedAtNanos = System.nanoTime()
            val finalized = capture.stopAndFinalize()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

            assertEquals(segment, finalized)
            assertTrue("stop took ${elapsedMs}ms", elapsedMs < QUICK_STOP_THRESHOLD_MS)
            // Two full 4096-byte buffers must be in the file: the pre-stop buffer and
            // the final partial buffer handed off after stop was signaled.
            assertTrue("file length ${segment.length()}", segment.length() >= 2 * EXPECTED_READ_BUFFER_BYTES)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun captureReadError_notifiesErrorListenerAndReleasesAudio() =
        runTest {
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } returns AudioRecord.ERROR_DEAD_OBJECT

            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val errors = CopyOnWriteArrayList<GaplessCaptureError>()
            val notified = CountDownLatch(1)
            capture.setCaptureErrorListener { error ->
                errors += error
                notified.countDown()
            }
            val segment = File(temporaryFolder.root, "dead-source.wav")

            capture.start(segment)

            assertTrue(notified.await(2, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertEquals(AudioRecord.ERROR_DEAD_OBJECT, errors.single().audioRecordErrorCode)
            verify { audioRecord.release() }

            val finalized = capture.stopAndFinalize()
            assertEquals(segment, finalized)
            assertTrue(segment.exists())
        }

    private companion object {
        const val QUICK_STOP_THRESHOLD_MS = 3_000L
        const val EXPECTED_READ_BUFFER_BYTES = 4_096L
    }
}
