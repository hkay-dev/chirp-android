package dev.chirpboard.app.core.audio.recorder

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.chirpboard.app.core.audio.AudioCaptureSession
import dev.chirpboard.app.core.audio.AudioGain
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import org.junit.Assert.assertNull
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
        every { Log.i(any(), any<String>()) } returns 0
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
        every { record.getTimestamp(any(), any()) } returns AudioRecord.ERROR_INVALID_OPERATION
        coEvery {
            selector.buildAudioRecord(any(), any(), any(), any(), any())
        } returns AudioCaptureSession(record, sessionToken = 1L)
        justRun { selector.observeRouting(any()) }
        justRun { selector.stopObservingRouting(any()) }
        justRun { selector.clearActiveDevice(any<Long>()) }
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class, SystemClock::class, ContextCompat::class, AudioRecord::class)
        cacheDir.deleteRecursively()
    }

    @Test
    fun `dead object recovery appends to the same durable capture`() =
        runBlocking {
            val fileRecorder = fileBackedRecorder()
            val replacement = mockk<AudioRecord>(relaxUnitFun = true)
            every { replacement.state } returns AudioRecord.STATE_INITIALIZED
            every { replacement.getTimestamp(any(), any()) } returns AudioRecord.ERROR_INVALID_OPERATION
            coEvery {
                selector.buildAudioRecord(any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    AudioCaptureSession(record, sessionToken = 1L),
                    AudioCaptureSession(replacement, sessionToken = 2L),
                )
            var originalReads = 0
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                if (originalReads++ == 0) {
                    firstArg<FloatArray>().fill(0.25f)
                    READ_BUFFER_SIZE
                } else {
                    AudioRecord.ERROR_DEAD_OBJECT
                }
            }
            var captured: VoiceRecorder.CapturedPcmFloatFile? = null
            var replacementReads = 0
            every { replacement.read(any<FloatArray>(), any(), any(), any()) } answers {
                if (replacementReads++ == 0) {
                    firstArg<FloatArray>().fill(0.5f)
                    READ_BUFFER_SIZE
                } else {
                    captured = fileRecorder.stopToFileBacked()
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }
            val errors = mutableListOf<RecordingError>()
            fileRecorder.onRecordingError = { errors.add(it) }

            assertTrue(fileRecorder.start())
            fileRecorder.collectSamples()

            assertTrue(errors.isEmpty())
            assertEquals(READ_BUFFER_SIZE * 2, requireNotNull(captured).sampleCount)
            assertEquals(1, requireNotNull(fileRecorder.latestIntegrityReport()).recorderRestartCount)
            verify { record.stop() }
            verify { record.release() }
            verify { replacement.startRecording() }
        }

    @Test
    fun `repeated zero reads restart capture and preserve the durable prefix`() =
        runBlocking {
            val fileRecorder = fileBackedRecorder()
            val replacement = mockk<AudioRecord>(relaxUnitFun = true)
            every { replacement.state } returns AudioRecord.STATE_INITIALIZED
            every { replacement.getTimestamp(any(), any()) } returns AudioRecord.ERROR_INVALID_OPERATION
            coEvery {
                selector.buildAudioRecord(any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    AudioCaptureSession(record, sessionToken = 1L),
                    AudioCaptureSession(replacement, sessionToken = 2L),
                )
            var originalReads = 0
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                originalReads += 1
                if (originalReads == 1) {
                    firstArg<FloatArray>().fill(0.25f)
                    READ_BUFFER_SIZE
                } else {
                    0
                }
            }
            var captured: VoiceRecorder.CapturedPcmFloatFile? = null
            var replacementReads = 0
            every { replacement.read(any<FloatArray>(), any(), any(), any()) } answers {
                if (replacementReads++ == 0) {
                    firstArg<FloatArray>().fill(0.5f)
                    READ_BUFFER_SIZE
                } else {
                    captured = fileRecorder.stopToFileBacked()
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }
            val errors = mutableListOf<RecordingError>()
            fileRecorder.onRecordingError = errors::add

            assertTrue(fileRecorder.start())
            fileRecorder.collectSamples()

            assertTrue(errors.isEmpty())
            assertEquals(READ_BUFFER_SIZE * 2, requireNotNull(captured).sampleCount)
            val report = requireNotNull(fileRecorder.latestIntegrityReport())
            assertEquals(1, report.watchdogRestartCount)
            assertEquals(1, report.recorderRestartCount)
        }

    @Test
    fun `file backed start refuses critically low storage before opening the microphone`() =
        runBlocking {
            val fileRecorder = fileBackedRecorder(availableBytes = VoiceRecorder.MIN_CAPTURE_FREE_BYTES - 1)
            val errors = mutableListOf<RecordingError>()
            fileRecorder.onRecordingError = errors::add

            assertFalse(fileRecorder.start())

            assertEquals(listOf<RecordingError>(RecordingError.StorageUnavailable), errors)
            coVerify(exactly = 0) { selector.buildAudioRecord(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { record.startRecording() }
        }

    @Test
    fun `file backed start converts storage inspection failure into a recorder error`() =
        runBlocking {
            val fileRecorder =
                fileBackedRecorder(
                    availableStorageBytes = { throw SecurityException("storage hidden") },
                )
            val errors = mutableListOf<RecordingError>()
            fileRecorder.onRecordingError = errors::add

            assertFalse(fileRecorder.start())

            assertEquals(listOf<RecordingError>(RecordingError.StorageUnavailable), errors)
            coVerify(exactly = 0) { selector.buildAudioRecord(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `immediate collection captures the first block before start is presented as ready`() =
        runBlocking {
            val fileRecorder = fileBackedRecorder()
            var reads = 0
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                reads += 1
                if (reads == 1) {
                    firstArg<FloatArray>().fill(0.25f)
                    READ_BUFFER_SIZE
                } else {
                    0
                }
            }

            assertTrue(fileRecorder.start(collectImmediately = true))
            fileRecorder.awaitFirstSamples()
            val capture = fileRecorder.stopToFileBacked()

            assertEquals(READ_BUFFER_SIZE, requireNotNull(capture).sampleCount)
            assertTrue(requireNotNull(fileRecorder.latestIntegrityReport()).sampleCount >= READ_BUFFER_SIZE)
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
            every { secondRecord.getTimestamp(any(), any()) } returns AudioRecord.ERROR_INVALID_OPERATION
            coEvery {
                selector.buildAudioRecord(any(), any(), any(), any(), any())
            } returns AudioCaptureSession(secondRecord, sessionToken = 2L)

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
                AudioCaptureSession(record, sessionToken = 1L)
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
    fun `failed init attempts token-clear their selector publication before retrying`() =
        runBlocking {
            // Each attempt publishes a fresh selector token; without the per-attempt
            // clear, the failed attempts' tokens would leak their publication — and
            // any communication-device hold a classic-BT selection took (MIC-006).
            var token = 0L
            coEvery { selector.buildAudioRecord(any(), any(), any(), any(), any()) } coAnswers {
                AudioCaptureSession(record, sessionToken = ++token)
            }
            val states =
                intArrayOf(
                    AudioRecord.STATE_UNINITIALIZED,
                    AudioRecord.STATE_UNINITIALIZED,
                    AudioRecord.STATE_INITIALIZED,
                )
            var stateReads = 0
            every { record.state } answers { states[minOf(stateReads++, states.lastIndex)] }

            assertTrue(recorder.start())

            verify(exactly = 1) { selector.clearActiveDevice(1L) }
            verify(exactly = 1) { selector.clearActiveDevice(2L) }
            // The successful third attempt's token is live and must not be cleared.
            verify(exactly = 0) { selector.clearActiveDevice(3L) }
        }

    @Test
    fun `stale read error after a restart does not tear down the new session`() =
        runBlocking {
            val errors = mutableListOf<RecordingError>()
            recorder.onRecordingError = { errors.add(it) }
            val secondRecord = mockk<AudioRecord>(relaxUnitFun = true)
            every { secondRecord.state } returns AudioRecord.STATE_INITIALIZED
            every { secondRecord.getTimestamp(any(), any()) } returns AudioRecord.ERROR_INVALID_OPERATION
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                // The old session is stopped and a new one started while this
                // read is still in flight; its failure must then be ignored.
                recorder.stop()
                coEvery {
                    selector.buildAudioRecord(any(), any(), any(), any(), any())
                } returns AudioCaptureSession(secondRecord, sessionToken = 2L)
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
    fun `file backed write failure keeps ownership until the empty capture is claimed`() =
        runBlocking {
            val failingOutput =
                object : OutputStream() {
                    override fun write(value: Int) = throw IOException("disk full")

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) = throw IOException("disk full")
                }
            val fileRecorder = fileBackedRecorder { failingOutput }
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
            assertEquals(1, captureFiles().size)
            assertNull(fileRecorder.stopToFileBacked())
            assertTrue(captureFiles().isEmpty())
        }

    @Test
    fun `file backed read error keeps captured samples for rescue`() =
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
            assertEquals(1, captureFiles().size)

            val captured = fileRecorder.stopToFileBacked()

            assertEquals(1024, captured?.sampleCount)
            assertEquals(1024L * Float.SIZE_BYTES, captured?.file?.length())
            assertTrue(captured?.file?.exists() == true)
        }

    @Test
    fun `in memory capture grows beyond the initial buffer and returns all samples`() =
        runBlocking {
            // One minute of audio is the lazy initial capacity; capture more so
            // the buffer must grow at least once past it.
            val readsToExceedInitialBuffer = (VoiceRecorder.SAMPLE_RATE * 60 / READ_BUFFER_SIZE) + 5
            var reads = 0
            var captured: FloatArray? = null
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                reads++
                if (reads <= readsToExceedInitialBuffer) {
                    firstArg<FloatArray>().fill(0.5f)
                    READ_BUFFER_SIZE
                } else {
                    captured = recorder.stop()
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }

            assertTrue(recorder.start())
            recorder.collectSamples()

            assertEquals(readsToExceedInitialBuffer * READ_BUFFER_SIZE, captured?.size)
            assertEquals(0.5f, captured?.first() ?: 0f, 0.0001f)
            assertEquals(0.5f, captured?.last() ?: 0f, 0.0001f)
        }

    @Test
    fun `file backed capture writes little-endian float pcm`() =
        runBlocking {
            val fileRecorder = fileBackedRecorder()
            fileRecorder.onRecordingError = {}
            var reads = 0
            var result: VoiceRecorder.CapturedPcmFloatFile? = null
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                reads++
                if (reads == 1) {
                    firstArg<FloatArray>().fill(0.5f)
                    READ_BUFFER_SIZE
                } else {
                    result = fileRecorder.stopToFileBacked()
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }

            assertTrue(fileRecorder.start())
            fileRecorder.collectSamples()

            val captured = requireNotNull(result)
            assertEquals(READ_BUFFER_SIZE, captured.sampleCount)
            val bytes = captured.file.readBytes()
            assertEquals(READ_BUFFER_SIZE * Float.SIZE_BYTES, bytes.size)
            val decoded =
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { floatBuffer ->
                    FloatArray(floatBuffer.remaining()).also(floatBuffer::get)
                }
            assertEquals(0.5f, decoded.first(), 0.0001f)
            assertEquals(0.5f, decoded.last(), 0.0001f)
        }

    @Test
    fun `file backed capture writes directly to a requested durable path`() =
        runBlocking {
            val fileRecorder = fileBackedRecorder()
            fileRecorder.onRecordingError = {}
            val requested = File(cacheDir, "durable/keyboard-live.f32pcm")
            assertTrue(requested.parentFile?.mkdirs() == true)
            assertTrue(requested.createNewFile())
            var reads = 0
            var result: VoiceRecorder.CapturedPcmFloatFile? = null
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                reads++
                if (reads == 1) {
                    firstArg<FloatArray>().fill(0.25f)
                    READ_BUFFER_SIZE
                } else {
                    result = fileRecorder.stopToFileBacked()
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }

            assertTrue(fileRecorder.start(requested))
            fileRecorder.collectSamples()

            val captured = requireNotNull(result)
            assertEquals(requested.canonicalPath, captured.file.canonicalPath)
            assertEquals(READ_BUFFER_SIZE * Float.SIZE_BYTES.toLong(), requested.length())
            assertTrue(captureFiles().isEmpty())
        }

    @Test
    fun `start captures with the recognition-tuned audio source`() =
        runBlocking {
            assertTrue(recorder.start())
            recorder.stop()

            coVerify {
                selector.buildAudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `sustained zero input reports silence and recovers when signal returns`() =
        runBlocking {
            val deliverSilence = java.util.concurrent.atomic.AtomicBoolean(true)
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                val buffer = firstArg<FloatArray>()
                buffer.fill(if (deliverSilence.get()) 0f else 0.1f)
                buffer.size
            }
            val transitions = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
            recorder.onSilenceStateChanged = { silenced ->
                transitions.add(silenced)
                if (silenced) {
                    // Un-silence the source so the recovery transition fires next.
                    deliverSilence.set(false)
                } else {
                    recorder.stop()
                }
            }

            assertTrue(recorder.start())
            recorder.collectSamples()

            assertEquals(listOf(true, false), transitions.toList())
        }

    @Test
    fun `configured gain boosts quiet samples linearly and soft-limits loud peaks`() =
        runBlocking {
            recorder.gainMultiplier = 4f
            var captured: FloatArray? = null
            var reads = 0
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                reads++
                if (reads == 1) {
                    val buffer = firstArg<FloatArray>()
                    // First half quiet (well below the knee after boost), second half loud
                    // (4x would hard-clip past full scale without the soft limiter).
                    for (i in buffer.indices) {
                        buffer[i] = if (i < buffer.size / 2) 0.1f else 0.3f
                    }
                    buffer.size
                } else {
                    captured = recorder.stop()
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }

            assertTrue(recorder.start())
            recorder.collectSamples()

            val samples = captured ?: error("no samples captured")
            // Below the knee the gain is applied exactly.
            assertEquals(0.4f, samples.first(), 0.0001f)
            // Above the knee the boosted peak is compressed, never clipped to or past 1.0.
            val boostedPeak = samples.last()
            assertTrue("peak=$boostedPeak", boostedPeak > AudioGain.SOFT_LIMIT_KNEE)
            assertTrue("peak=$boostedPeak", boostedPeak < 1.0f)
        }

    @Test
    fun `zero input shorter than the warning threshold never reports silence`() =
        runBlocking {
            // 62 all-zero reads = 63,488 samples, just below SILENCE_WARNING_SAMPLES (4s
            // at 16kHz = 64,000): the warning must not fire for brief quiet stretches.
            val zeroReadsJustBelowThreshold =
                (VoiceRecorder.SILENCE_WARNING_SAMPLES / READ_BUFFER_SIZE).toInt() - 1
            val transitions = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
            recorder.onSilenceStateChanged = { transitions.add(it) }
            var reads = 0
            every { record.read(any<FloatArray>(), any(), any(), any()) } answers {
                reads++
                if (reads <= zeroReadsJustBelowThreshold) {
                    firstArg<FloatArray>().fill(0f)
                    READ_BUFFER_SIZE
                } else {
                    recorder.stop()
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }

            assertTrue(recorder.start())
            recorder.collectSamples()

            assertTrue("unexpected silence transitions: $transitions", transitions.isEmpty())
        }

    @Test
    fun `stop racing a new start leaves the new session recording`() =
        runBlocking {
            // Sub-minimum session duration: a raced stop must not judge (and
            // report TooShort for) the new session it no longer owns.
            every { SystemClock.elapsedRealtime() } answers {
                clockMs += 100L
                clockMs
            }
            val errors = mutableListOf<RecordingError>()
            recorder.onRecordingError = { errors.add(it) }
            assertTrue(recorder.start())

            val secondRecord = mockk<AudioRecord>(relaxUnitFun = true)
            every { secondRecord.state } returns AudioRecord.STATE_INITIALIZED
            every { secondRecord.getTimestamp(any(), any()) } returns AudioRecord.ERROR_INVALID_OPERATION
            recorder.afterStopAudioRecordForTest = {
                // A fresh start() publishes a new session inside the gap between
                // the stop's two lock blocks; the stop must leave it untouched.
                recorder.afterStopAudioRecordForTest = null
                coEvery {
                    selector.buildAudioRecord(any(), any(), any(), any(), any())
                } returns AudioCaptureSession(secondRecord, sessionToken = 2L)
                runBlocking { assertTrue(recorder.start()) }
            }

            val captured = recorder.stop()

            assertEquals(0, captured.size)
            assertTrue(errors.isEmpty())
            assertTrue(recorder.isRecording())
            verify { secondRecord.startRecording() }
            verify(exactly = 0) { secondRecord.stop() }
            verify(exactly = 0) { secondRecord.release() }
        }

    @Test
    fun `stopToFileBacked racing a new start never steals the new session's capture`() =
        runBlocking {
            val fileRecorder = fileBackedRecorder()
            fileRecorder.onRecordingError = {}
            assertTrue(fileRecorder.start())

            val secondRecord = mockk<AudioRecord>(relaxUnitFun = true)
            every { secondRecord.state } returns AudioRecord.STATE_INITIALIZED
            every { secondRecord.getTimestamp(any(), any()) } returns AudioRecord.ERROR_INVALID_OPERATION
            fileRecorder.afterStopAudioRecordForTest = {
                fileRecorder.afterStopAudioRecordForTest = null
                coEvery {
                    selector.buildAudioRecord(any(), any(), any(), any(), any())
                } returns AudioCaptureSession(secondRecord, sessionToken = 2L)
                runBlocking { assertTrue(fileRecorder.start()) }
            }

            val stolen = fileRecorder.stopToFileBacked()

            assertNull(stolen)
            assertTrue(fileRecorder.isRecording())
            // The new session's capture file survives the racing stop.
            assertEquals(1, captureFiles().size)
            verify { secondRecord.startRecording() }
            verify(exactly = 0) { secondRecord.stop() }
            verify(exactly = 0) { secondRecord.release() }

            // The new session's output stream stayed intact: a normal capture
            // and stop still return its audio.
            var result: VoiceRecorder.CapturedPcmFloatFile? = null
            var reads = 0
            every { secondRecord.read(any<FloatArray>(), any(), any(), any()) } answers {
                reads++
                if (reads == 1) {
                    firstArg<FloatArray>().fill(0.5f)
                    READ_BUFFER_SIZE
                } else {
                    result = fileRecorder.stopToFileBacked()
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }
            fileRecorder.collectSamples()

            assertEquals(READ_BUFFER_SIZE, requireNotNull(result).sampleCount)
        }

    @Test
    fun `stop clears the selector's active device exactly once with the session token`() =
        runBlocking {
            assertTrue(recorder.start())

            recorder.stop()
            // A second stop must not clear again (the token was already handed back).
            recorder.stop()

            verify(exactly = 1) { selector.clearActiveDevice(1L) }
        }

    @Test
    fun `stopToFileBacked clears the selector's active device with the session token`() =
        runBlocking {
            val fileRecorder = fileBackedRecorder()
            fileRecorder.onRecordingError = {}
            assertTrue(fileRecorder.start())

            fileRecorder.stopToFileBacked()

            verify(exactly = 1) { selector.clearActiveDevice(1L) }
        }

    @Test
    fun `cancelCapture clears the selector's active device with the session token`() =
        runBlocking {
            assertTrue(recorder.start())

            recorder.cancelCapture()

            verify(exactly = 1) { selector.clearActiveDevice(1L) }
        }

    @Test
    fun `routing is observed after start and removed before release on stop`() =
        runBlocking {
            assertTrue(recorder.start())
            recorder.stop()

            verifyOrder {
                record.startRecording()
                selector.observeRouting(record)
                selector.stopObservingRouting(record)
                record.release()
            }
        }

    private fun fileBackedRecorder(
        availableBytes: Long = Long.MAX_VALUE,
        availableStorageBytes: ((File) -> Long)? = null,
        captureOutputFactory: ((File) -> OutputStream)? = null,
    ): VoiceRecorder {
        every { context.cacheDir } returns cacheDir
        return VoiceRecorder(
            context = context,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            inputDeviceSelector = selector,
            captureStorageMode = VoiceRecorder.CaptureStorageMode.FileBacked,
            captureOutputFactory = captureOutputFactory ?: { file -> java.io.FileOutputStream(file) },
            availableStorageBytes = availableStorageBytes ?: { availableBytes },
        )
    }

    private fun captureFiles(): List<File> = File(cacheDir, VoiceRecorder.KEYBOARD_CAPTURE_CACHE_DIR).listFiles()?.toList().orEmpty()

    private companion object {
        /** Float samples returned per simulated [AudioRecord] read, mirroring the recorder's read buffer size. */
        const val READ_BUFFER_SIZE = 1024
    }
}
