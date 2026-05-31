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
}
