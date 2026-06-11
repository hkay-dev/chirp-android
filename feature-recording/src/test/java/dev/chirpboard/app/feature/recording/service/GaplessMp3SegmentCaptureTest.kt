package dev.chirpboard.app.feature.recording.service

import android.media.AudioRecord
import android.os.Process
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
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

/**
 * Behavioral coverage for the MP3 engine's stop/error/final-buffer paths, mirroring
 * [GaplessWavSegmentCaptureTest]. The native LAME binding cannot load on the JVM, so the
 * tests inject a deterministic [Mp3FrameEncoder] through the engine's internal constructor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GaplessMp3SegmentCaptureTest {
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
        unmockkStatic(AudioRecord::class)
        unmockkStatic(Process::class)
    }

    private fun newCapture(): GaplessMp3SegmentCapture =
        GaplessMp3SegmentCapture(
            inputDeviceSelector = inputDeviceSelector,
            sampleRate = 16_000,
            encoderFactory = { FakeMp3FrameEncoder() },
        )

    @Test
    fun rotateSegment_whenNotRunning_returnsFailed() {
        val capture = newCapture()
        val nextSegment = File(temporaryFolder.root, "next.mp3")

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
            val segment = File(temporaryFolder.root, "read-error.mp3")

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

            val capture = newCapture()
            val errors = CopyOnWriteArrayList<GaplessCaptureError>()
            capture.setCaptureErrorListener { errors += it }
            val segment = File(temporaryFolder.root, "blocked-read.mp3")

            capture.start(segment)
            Thread.sleep(150)

            val startedAtNanos = System.nanoTime()
            val finalized = capture.stopAndFinalize()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

            assertEquals(segment, finalized)
            assertTrue("stop took ${elapsedMs}ms", elapsedMs < QUICK_STOP_THRESHOLD_MS)
            // Two encoded chunks must be in the file (the pre-stop buffer and the final
            // buffer handed off after stop was signaled), plus the encoder's final flush.
            assertTrue(
                "file length ${segment.length()}",
                segment.length() >= 2 * ENCODED_CHUNK_BYTES + FLUSHED_BYTES,
            )
            assertTrue(errors.isEmpty())
        }

    @Test
    fun captureReadError_notifiesErrorListenerOnceAndReleasesAudio() =
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
            val segment = File(temporaryFolder.root, "dead-source.mp3")

            capture.start(segment)

            assertTrue(notified.await(2, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertEquals(AudioRecord.ERROR_DEAD_OBJECT, errors.single().audioRecordErrorCode)
            verify { audioRecord.release() }
            // failCapture flushes the encoder before closing, so the partial segment keeps
            // whatever the encoder still buffered.
            assertTrue("file length ${segment.length()}", segment.length() >= FLUSHED_BYTES)

            val finalized = capture.stopAndFinalize()
            assertEquals(segment, finalized)
            assertTrue(segment.exists())
        }

    private companion object {
        const val QUICK_STOP_THRESHOLD_MS = 3_000L
        const val ENCODED_CHUNK_BYTES = 512L
        const val FLUSHED_BYTES = 256L
    }
}

/** Writes a fixed number of marker bytes per encode/flush so file sizes are predictable. */
private class FakeMp3FrameEncoder : Mp3FrameEncoder {
    override fun encode(
        leftChannel: ShortArray,
        rightChannel: ShortArray,
        sampleCount: Int,
        mp3Buffer: ByteArray,
    ): Int {
        if (sampleCount <= 0) return 0
        mp3Buffer.fill(0x33, 0, ENCODED_CHUNK_BYTES)
        return ENCODED_CHUNK_BYTES
    }

    override fun flush(mp3Buffer: ByteArray): Int {
        mp3Buffer.fill(0x44, 0, FLUSHED_BYTES)
        return FLUSHED_BYTES
    }

    private companion object {
        const val ENCODED_CHUNK_BYTES = 512
        const val FLUSHED_BYTES = 256
    }
}
