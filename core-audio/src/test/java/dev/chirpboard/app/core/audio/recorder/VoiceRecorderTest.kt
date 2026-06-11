package dev.chirpboard.app.core.audio.recorder

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceRecorderTest {
    private val context = mockk<Context>(relaxed = true)
    private val selector = mockk<AudioInputDeviceSelector>()
    private val record = mockk<AudioRecord>(relaxUnitFun = true)
    private val cacheDir = Files.createTempDirectory("voice-recorder-test").toFile()
    private var clockMs = 0L

    private val recorder by lazy {
        VoiceRecorder(
            context = context,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            inputDeviceSelector = selector,
        )
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers {
            clockMs += 1000L
            clockMs
        }

        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        mockkStatic(AudioRecord::class)
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 4096

        every { record.state } returns AudioRecord.STATE_INITIALIZED
        coEvery { selector.buildAudioRecord(any(), any(), any(), any(), any()) } returns record
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class, SystemClock::class, ContextCompat::class, AudioRecord::class)
        cacheDir.deleteRecursively()
    }

    @Test
    fun `dead object read error stops and releases the recorder`() =
        runBlocking {
            every { record.read(any<FloatArray>(), any(), any(), any()) } returns AudioRecord.ERROR_DEAD_OBJECT
            val errors = mutableListOf<RecordingError>()
            recorder.onRecordingError = { errors.add(it) }

            assertTrue(recorder.start())
            recorder.collectSamples()

            assertEquals(listOf<RecordingError>(RecordingError.DeadObject), errors)
            assertFalse(recorder.isRecording())
            verify { record.stop() }
            verify { record.release() }
        }

    @Test
    fun `generic read error stops and releases the recorder`() =
        runBlocking {
            every { record.read(any<FloatArray>(), any(), any(), any()) } returns -99
            val errors = mutableListOf<RecordingError>()
            recorder.onRecordingError = { errors.add(it) }

            assertTrue(recorder.start())
            recorder.collectSamples()

            assertEquals(listOf<RecordingError>(RecordingError.Generic(-99)), errors)
            assertFalse(recorder.isRecording())
            verify { record.release() }
        }

    @Test
    fun `recorder can start again after a read error released it`() =
        runBlocking {
            every { record.read(any<FloatArray>(), any(), any(), any()) } returns AudioRecord.ERROR_INVALID_OPERATION
            recorder.onRecordingError = {}

            assertTrue(recorder.start())
            recorder.collectSamples()
            assertFalse(recorder.isRecording())
            verify { record.release() }

            val secondRecord = mockk<AudioRecord>(relaxUnitFun = true)
            every { secondRecord.state } returns AudioRecord.STATE_INITIALIZED
            coEvery { selector.buildAudioRecord(any(), any(), any(), any(), any()) } returns secondRecord

            assertTrue(recorder.start())
            assertTrue(recorder.isRecording())
            verify { secondRecord.startRecording() }
        }

    @Test
    fun `startRecording failure releases the recorder and returns false`() =
        runBlocking {
            every { record.startRecording() } throws IllegalStateException("boom")

            assertFalse(recorder.start())

            assertFalse(recorder.isRecording())
            verify { record.release() }
        }

    @Test
    fun `cancellation after startRecording releases the microphone`() =
        runBlocking {
            val job = launch(start = CoroutineStart.LAZY) { recorder.start() }
            every { record.startRecording() } answers { job.cancel() }

            job.start()
            job.join()

            assertTrue(job.isCancelled)
            assertFalse(recorder.isRecording())
            verify { record.stop() }
            verify { record.release() }
        }

    @Test
    fun `stop returns captured samples and read errors after stop are suppressed`() =
        runBlocking {
            val errors = mutableListOf<RecordingError>()
            recorder.onRecordingError = { errors.add(it) }
            var captured: FloatArray? = null
            var reads = 0
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                reads++
                if (reads == 1) {
                    val buffer = firstArg<FloatArray>()
                    buffer.fill(0.25f)
                    buffer.size
                } else {
                    captured = recorder.stop()
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }

            assertTrue(recorder.start())
            recorder.collectSamples()

            assertEquals(1024, captured?.size)
            assertEquals(0.25f, captured?.first() ?: 0f, 0.0001f)
            assertTrue(errors.isEmpty())
            assertFalse(recorder.isRecording())
            verify { record.release() }
        }

    @Test
    fun `cancelled start that bails on an active session leaves it recording`() =
        runBlocking {
            assertTrue(recorder.start())
            assertTrue(recorder.isRecording())

            val job = launch(start = CoroutineStart.LAZY) { recorder.start() }
            every { ContextCompat.checkSelfPermission(any(), any()) } answers {
                job.cancel()
                PackageManager.PERMISSION_GRANTED
            }

            job.start()
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(recorder.isRecording())
            verify(exactly = 0) { record.stop() }
            verify(exactly = 0) { record.release() }
        }

    @Test
    fun `cancellation during init retry aborts the attempt and unblocks collectors`() =
        runBlocking {
            every { record.state } returns AudioRecord.STATE_UNINITIALIZED
            val initStarted = CompletableDeferred<Unit>()
            coEvery { selector.buildAudioRecord(any(), any(), any(), any(), any()) } coAnswers {
                initStarted.complete(Unit)
                record
            }

            val job = launch(Dispatchers.IO) { recorder.start() }
            initStarted.await()
            val collector = launch(Dispatchers.IO) { recorder.collectSamples() }
            job.cancel()
            job.join()
            collector.join()

            assertTrue(job.isCancelled)
            assertFalse(recorder.isRecording())
            verify(exactly = 0) { record.startRecording() }

            // The recorder remains usable after the aborted attempt.
            every { record.state } returns AudioRecord.STATE_INITIALIZED
            assertTrue(recorder.start())
            assertTrue(recorder.isRecording())
            verify { record.startRecording() }
        }

    @Test
    fun `stale read error after a restart does not tear down the new session`() =
        runBlocking {
            val errors = mutableListOf<RecordingError>()
            recorder.onRecordingError = { errors.add(it) }
            val secondRecord = mockk<AudioRecord>(relaxUnitFun = true)
            every { secondRecord.state } returns AudioRecord.STATE_INITIALIZED
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                // The old session is stopped and a new one started while this
                // read is still in flight; its failure must then be ignored.
                recorder.stop()
                coEvery { selector.buildAudioRecord(any(), any(), any(), any(), any()) } returns secondRecord
                runBlocking { assertTrue(recorder.start()) }
                AudioRecord.ERROR_DEAD_OBJECT
            }

            assertTrue(recorder.start())
            recorder.collectSamples()

            assertTrue(errors.isEmpty())
            assertTrue(recorder.isRecording())
            verify { secondRecord.startRecording() }
            verify(exactly = 0) { secondRecord.stop() }
            verify(exactly = 0) { secondRecord.release() }
        }

    @Test
    fun `file backed write failure reports storage unavailable and cleans up`() =
        runBlocking {
            mockkConstructor(BufferedOutputStream::class)
            every { anyConstructed<BufferedOutputStream>().write(any<ByteArray>()) } throws IOException("disk full")
            try {
                val fileRecorder = fileBackedRecorder()
                val errors = mutableListOf<RecordingError>()
                fileRecorder.onRecordingError = { errors.add(it) }
                every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                    firstArg<FloatArray>().fill(0.25f)
                    1024
                }

                assertTrue(fileRecorder.start())
                fileRecorder.collectSamples()

                assertEquals(listOf<RecordingError>(RecordingError.StorageUnavailable), errors)
                assertFalse(fileRecorder.isRecording())
                verify { record.stop() }
                verify { record.release() }
                assertTrue(captureFiles().isEmpty())
            } finally {
                unmockkConstructor(BufferedOutputStream::class)
            }
        }

    @Test
    fun `file backed read error deletes the capture temp file`() =
        runBlocking {
            val fileRecorder = fileBackedRecorder()
            val errors = mutableListOf<RecordingError>()
            fileRecorder.onRecordingError = { errors.add(it) }
            var reads = 0
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                reads++
                if (reads == 1) {
                    firstArg<FloatArray>().fill(0.5f)
                    1024
                } else {
                    AudioRecord.ERROR_BAD_VALUE
                }
            }

            assertTrue(fileRecorder.start())
            assertEquals(1, captureFiles().size)
            fileRecorder.collectSamples()

            assertEquals(listOf<RecordingError>(RecordingError.BadValue), errors)
            assertFalse(fileRecorder.isRecording())
            verify { record.stop() }
            verify { record.release() }
            assertTrue(captureFiles().isEmpty())
        }

    private fun fileBackedRecorder(): VoiceRecorder {
        every { context.cacheDir } returns cacheDir
        return VoiceRecorder(
            context = context,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            inputDeviceSelector = selector,
            captureStorageMode = VoiceRecorder.CaptureStorageMode.FileBacked,
        )
    }

    private fun captureFiles(): List<File> = File(cacheDir, VoiceRecorder.KEYBOARD_CAPTURE_CACHE_DIR).listFiles()?.toList().orEmpty()
}
