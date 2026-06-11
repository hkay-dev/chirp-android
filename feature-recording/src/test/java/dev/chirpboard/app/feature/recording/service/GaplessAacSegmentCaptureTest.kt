package dev.chirpboard.app.feature.recording.service

import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Process
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkConstructor
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
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Behavioral coverage for the AAC engine's stop/error/final-buffer paths, mirroring
 * [GaplessWavSegmentCaptureTest]. MediaCodec and MediaMuxer are mocked, so the assertions
 * track the PCM bytes handed to the codec instead of bytes on disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GaplessAacSegmentCaptureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var inputDeviceSelector: AudioInputDeviceSelector
    private lateinit var audioRecord: AudioRecord
    private lateinit var codec: MediaCodec
    private val queuedPcmBytes = AtomicLong(0)
    private val endOfStreamQueued = AtomicBoolean(false)

    @Before
    fun setUp() {
        mockkStatic(AudioRecord::class)
        mockkStatic(Process::class)
        mockkStatic(MediaCodec::class)
        mockkStatic(MediaFormat::class)
        mockkConstructor(MediaMuxer::class)
        every { Process.setThreadPriority(any()) } just runs
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 2048
        every { MediaFormat.createAudioFormat(any(), any(), any()) } returns mockk(relaxed = true)
        every { anyConstructed<MediaMuxer>().stop() } just runs
        every { anyConstructed<MediaMuxer>().release() } just runs

        queuedPcmBytes.set(0)
        endOfStreamQueued.set(false)
        codec = mockk(relaxed = true)
        every { codec.dequeueInputBuffer(any()) } returns 0
        every { codec.getInputBuffer(any()) } answers { ByteBuffer.allocate(INPUT_BUFFER_CAPACITY) }
        every { codec.queueInputBuffer(any(), any(), any(), any(), any()) } answers {
            queuedPcmBytes.addAndGet(arg<Int>(2).toLong())
            if (arg<Int>(4) and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                endOfStreamQueued.set(true)
            }
        }
        every { codec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
        every { MediaCodec.createEncoderByType(any()) } returns codec

        audioRecord = mockk(relaxed = true)
        every { audioRecord.state } returns AudioRecord.STATE_INITIALIZED
        every { audioRecord.startRecording() } just runs
        every { audioRecord.stop() } just runs
        every { audioRecord.release() } just runs
        every {
            audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
        } answers {
            val buffer = firstArg<ByteArray>()
            buffer.fill(0x01)
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
        unmockkConstructor(MediaMuxer::class)
        unmockkStatic(MediaFormat::class)
        unmockkStatic(MediaCodec::class)
        unmockkStatic(AudioRecord::class)
        unmockkStatic(Process::class)
    }

    private fun newCapture(): GaplessAacSegmentCapture =
        GaplessAacSegmentCapture(
            inputDeviceSelector = inputDeviceSelector,
            sampleRate = 16_000,
            bitRate = 128_000,
        )

    @Test
    fun rotateSegment_whenNotRunning_returnsFailed() {
        val capture = newCapture()
        val nextSegment = File(temporaryFolder.root, "next.m4a")

        val result = capture.rotateSegment(nextSegment)

        assertTrue(result is SegmentRotationResult.Failed)
    }

    @Test
    fun stopAndFinalize_afterAudioRecordReadError_returnsWithoutHanging() =
        runTest {
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } returns AudioRecord.ERROR_DEAD_OBJECT

            val capture = newCapture()
            val segment = File(temporaryFolder.root, "read-error.m4a")

            capture.start(segment)
            Thread.sleep(100)

            val finalized = capture.stopAndFinalize()

            assertEquals(segment, finalized)
        }

    @Test
    fun stopAndFinalize_whileReadBlocked_returnsQuicklyAndQueuesFinalBuffer() =
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

            val capture = newCapture()
            val errors = CopyOnWriteArrayList<GaplessCaptureError>()
            capture.setCaptureErrorListener { errors += it }
            val segment = File(temporaryFolder.root, "blocked-read.m4a")

            capture.start(segment)
            Thread.sleep(150)

            val startedAtNanos = System.nanoTime()
            val finalized = capture.stopAndFinalize()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

            assertEquals(segment, finalized)
            assertTrue("stop took ${elapsedMs}ms", elapsedMs < QUICK_STOP_THRESHOLD_MS)
            // Two full 4096-byte buffers must have reached the codec: the pre-stop buffer
            // and the final partial buffer handed off after stop was signaled.
            assertTrue(
                "queued ${queuedPcmBytes.get()} bytes",
                queuedPcmBytes.get() >= 2 * EXPECTED_READ_BUFFER_BYTES,
            )
            assertTrue("end of stream was never queued", endOfStreamQueued.get())
            assertTrue(errors.isEmpty())
            verify { audioRecord.release() }
            verify { codec.release() }
        }

    @Test
    fun captureReadError_notifiesErrorListenerOnceAndReleasesResources() =
        runTest {
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } returns AudioRecord.ERROR_DEAD_OBJECT

            val capture = newCapture()
            val errors = CopyOnWriteArrayList<GaplessCaptureError>()
            val notified = CountDownLatch(1)
            capture.setCaptureErrorListener { error ->
                errors += error
                notified.countDown()
            }
            val segment = File(temporaryFolder.root, "dead-source.m4a")

            capture.start(segment)

            assertTrue(notified.await(2, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertEquals(AudioRecord.ERROR_DEAD_OBJECT, errors.single().audioRecordErrorCode)
            assertTrue("end of stream was never queued", endOfStreamQueued.get())
            verify { audioRecord.release() }
            verify { codec.release() }
            verify { anyConstructed<MediaMuxer>().release() }

            val finalized = capture.stopAndFinalize()
            assertEquals(segment, finalized)
        }

    private companion object {
        const val QUICK_STOP_THRESHOLD_MS = 3_000L
        const val EXPECTED_READ_BUFFER_BYTES = 4_096L
        const val INPUT_BUFFER_CAPACITY = 16_384
    }
}
