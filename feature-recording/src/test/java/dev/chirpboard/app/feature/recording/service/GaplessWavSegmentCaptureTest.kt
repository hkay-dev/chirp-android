package dev.chirpboard.app.feature.recording.service

import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import dev.chirpboard.app.core.audio.AudioCaptureSession
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
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

        // relaxUnitFun: the engine drives observeRouting/stopObservingRouting and the
        // token-gated clearActiveDevice on every start/release; only buildAudioRecord
        // needs explicit stubbing.
        inputDeviceSelector = mockk(relaxUnitFun = true)
        coEvery {
            inputDeviceSelector.buildAudioRecord(
                audioSource = any(),
                sampleRate = any(),
                channelConfig = any(),
                audioFormat = any(),
                bufferSize = any(),
            )
        } returns AudioCaptureSession(audioRecord, sessionToken = 1L)
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
    fun pauseAndFinalizeSegment_racingCaptureReadError_returnsAccurateFinalizedHeader() =
        runTest {
            // The first read delivers a full buffer; the second read fails with a dead
            // source at the same moment the test issues a pause, so pause and failCapture
            // race for the engine. Whichever order they interleave in, pause must never
            // return while the WAV header still carries placeholder sizes.
            val firstReadDone = AtomicBoolean(false)
            val pauseRequested = CountDownLatch(1)
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } answers {
                val buffer = firstArg<ByteArray>()
                if (!firstReadDone.getAndSet(true)) {
                    buffer.fill(0x01)
                    buffer.size
                } else {
                    pauseRequested.await(6, TimeUnit.SECONDS)
                    AudioRecord.ERROR_DEAD_OBJECT
                }
            }

            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val errors = CopyOnWriteArrayList<GaplessCaptureError>()
            capture.setCaptureErrorListener { errors += it }
            val segment = File(temporaryFolder.root, "pause-race.wav")

            capture.start(segment)
            Thread.sleep(150)

            pauseRequested.countDown()
            val startedAtNanos = System.nanoTime()
            val finalized = capture.pauseAndFinalizeSegment()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

            assertEquals(segment, finalized)
            assertTrue("pause took ${elapsedMs}ms", elapsedMs < QUICK_STOP_THRESHOLD_MS)
            assertTrue(
                "header sizes were not finalized before pause returned",
                WavFileWriter.hasAccurateHeader(segment),
            )
            assertTrue(
                "file length ${segment.length()}",
                segment.length() >= WavFileWriter.WAV_HEADER_BYTES + EXPECTED_READ_BUFFER_BYTES,
            )
            assertTrue("listener notified ${errors.size} times", errors.size <= 1)
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

    @Test
    fun pauseAndFinalizeSegment_drainsBufferedAudioLikeStop() =
        runTest {
            // Same HAL model as the blocked-read stop test: one pre-pause buffer, then a
            // final buffer that only becomes available once AudioRecord.stop() lands. The
            // pause path must hand that tail off exactly like stop does (AUD pause-drain).
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
            val segment = File(temporaryFolder.root, "pause-drain.wav")

            capture.start(segment)
            Thread.sleep(150)

            val finalized = capture.pauseAndFinalizeSegment()

            assertEquals(segment, finalized)
            assertTrue(
                "file length ${segment.length()} should include the drained tail buffer",
                segment.length() >= 2 * EXPECTED_READ_BUFFER_BYTES,
            )
        }

    @Test
    fun captureAppliesConfiguredGainToWrittenPcm() =
        runTest {
            val quietSample = 1_000
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } answers {
                val buffer = firstArg<ByteArray>()
                var index = 0
                while (index + 1 < buffer.size) {
                    buffer[index] = (quietSample and 0xFF).toByte()
                    buffer[index + 1] = ((quietSample shr 8) and 0xFF).toByte()
                    index += 2
                }
                buffer.size
            }

            val capture =
                GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000, gainMultiplier = 2f)
            val segment = File(temporaryFolder.root, "gain.wav")

            capture.start(segment)
            Thread.sleep(150)
            capture.stopAndFinalize()

            val payload = segment.readBytes()
            val firstSample =
                (
                    (payload[WavFileWriter.WAV_HEADER_BYTES + 1].toInt() shl 8) or
                        (payload[WavFileWriter.WAV_HEADER_BYTES].toInt() and 0xFF)
                ).toShort().toInt()
            // 1000 / 32767 * 2 = ~0.061: well below the soft-limit knee, so exactly doubled
            // (within one LSB of the float round-trip).
            assertTrue("firstSample=$firstSample", firstSample in 1_999..2_001)
        }

    @Test
    fun captureSoftLimitsLoudBoostedPeaks_insteadOfClippingOrWrapping() =
        runTest {
            // 30,000 * 2 = 60,000 — far past PCM16 full scale. Without the soft limiter
            // this either hard-clips (audible distortion) or wraps negative (garbage).
            val loudSample = 30_000
            val firstReadDone = AtomicBoolean(false)
            val stopSignal = CountDownLatch(1)
            every { audioRecord.stop() } answers { stopSignal.countDown() }
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } answers {
                val buffer = firstArg<ByteArray>()
                if (!firstReadDone.getAndSet(true)) {
                    var index = 0
                    while (index + 1 < buffer.size) {
                        buffer[index] = (loudSample and 0xFF).toByte()
                        buffer[index + 1] = ((loudSample shr 8) and 0xFF).toByte()
                        index += 2
                    }
                    buffer.size
                } else {
                    stopSignal.await(6, TimeUnit.SECONDS)
                    0
                }
            }

            val capture =
                GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000, gainMultiplier = 2f)
            val segment = File(temporaryFolder.root, "soft-limit.wav")

            capture.start(segment)
            capture.stopAndFinalize()

            val payload = segment.readBytes()
            val firstSample =
                (
                    (payload[WavFileWriter.WAV_HEADER_BYTES + 1].toInt() shl 8) or
                        (payload[WavFileWriter.WAV_HEADER_BYTES].toInt() and 0xFF)
                ).toShort().toInt()
            val kneeFloor = (0.85f * Short.MAX_VALUE).toInt()
            assertTrue("firstSample=$firstSample wrapped negative", firstSample > 0)
            assertTrue("firstSample=$firstSample below the knee", firstSample > kneeFloor)
            assertTrue("firstSample=$firstSample exceeds full scale", firstSample <= Short.MAX_VALUE.toInt())
        }

    @Test
    fun captureUsesTheNaturalMicSource_notTheRecognitionTunedOne() =
        runTest {
            // Deliberate asymmetry: recorder-app captures keep natural audio (MIC), while
            // ASR surfaces (VoiceRecorder) use VOICE_RECOGNITION. A silent swap here would
            // change the sound of every saved recording.
            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val segment = File(temporaryFolder.root, "source.wav")

            capture.start(segment)
            capture.stopAndFinalize()

            coVerify {
                inputDeviceSelector.buildAudioRecord(
                    audioSource = MediaRecorder.AudioSource.MIC,
                    sampleRate = any(),
                    channelConfig = any(),
                    audioFormat = any(),
                    bufferSize = any(),
                )
            }
        }

    @Test
    fun sustainedZeroInput_notifiesSilenceListener_andRecovers() =
        runTest {
            val recovered = AtomicBoolean(false)
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } answers {
                val buffer = firstArg<ByteArray>()
                if (recovered.get()) {
                    buffer.fill(0x01)
                } else {
                    buffer.fill(0x00)
                }
                buffer.size
            }

            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val silenced = CountDownLatch(1)
            val unsilenced = CountDownLatch(1)
            capture.setSilenceListener { isSilenced ->
                if (isSilenced) silenced.countDown() else unsilenced.countDown()
            }
            val segment = File(temporaryFolder.root, "silence.wav")

            capture.start(segment)

            assertTrue("silence never reported", silenced.await(5, TimeUnit.SECONDS))
            recovered.set(true)
            assertTrue("recovery never reported", unsilenced.await(5, TimeUnit.SECONDS))

            capture.stopAndFinalize()
        }

    @Test
    fun writerFailureOnCaptureThread_routesThroughErrorListener_neverCrashes() =
        runTest {
            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val errors = CopyOnWriteArrayList<GaplessCaptureError>()
            val notified = CountDownLatch(1)
            capture.setCaptureErrorListener { error ->
                errors += error
                notified.countDown()
            }
            val segment = File(temporaryFolder.root, "write-fail.wav")

            capture.start(segment)
            Thread.sleep(150)

            // Force the next segment file creation (on the capture thread, inside the
            // write/rotate block) to throw an IOException: the rotation target's parent
            // is an existing FILE, so the writer cannot be created. Pre-fix this unwound
            // the raw capture thread into the default handler and killed the process.
            val blocker = temporaryFolder.newFile("blocker")
            val badTarget = File(blocker, "impossible.wav")
            capture.rotateSegment(badTarget)

            assertTrue("write failure never reported", notified.await(5, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertTrue(errors.single().message.contains("WAV capture failed"))
            // The partial segment was finalized with an accurate header (stop-with-save).
            assertTrue(WavFileWriter.hasAccurateHeader(segment))
            verify { audioRecord.release() }
        }

    @Test
    fun startWhileRunning_throwsAndReleasesOnlyTheFreshlyBuiltRecord() =
        runTest {
            // The misuse start builds its own record + session token; pre-fix the
            // require threw without releasing it, leaking the native handle (MIC-021).
            val secondRecord = mockk<AudioRecord>(relaxed = true)
            every { secondRecord.state } returns AudioRecord.STATE_INITIALIZED
            coEvery {
                inputDeviceSelector.buildAudioRecord(
                    audioSource = any(),
                    sampleRate = any(),
                    channelConfig = any(),
                    audioFormat = any(),
                    bufferSize = any(),
                )
            } returns AudioCaptureSession(audioRecord, sessionToken = 1L) andThen
                AudioCaptureSession(secondRecord, sessionToken = 2L)

            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val firstSegment = File(temporaryFolder.root, "running.wav")
            capture.start(firstSegment)
            Thread.sleep(100)

            val secondStart = runCatching { capture.start(File(temporaryFolder.root, "rejected.wav")) }

            assertTrue(secondStart.exceptionOrNull() is IllegalArgumentException)
            verify { secondRecord.release() }
            // The dead session's publication is token-cleared; the live session's
            // record stays untouched until its own stop.
            verify { inputDeviceSelector.clearActiveDevice(2L) }
            verify(exactly = 0) { audioRecord.release() }

            val finalized = capture.stopAndFinalize()
            assertEquals(firstSegment, finalized)
        }

    @Test
    fun rotateSegment_racingEntries_admitExactlyOneRotation() =
        runTest {
            // The first read delivers a buffer; later reads block until the gate opens,
            // so an installed rotation target cannot be consumed while two contenders
            // race through the entry check. Pre-fix the check and the latch install
            // were separate lock blocks, so both racers could believe they owned the
            // pending rotation (MIC-021).
            val firstReadDone = AtomicBoolean(false)
            val readGate = CountDownLatch(1)
            every {
                audioRecord.read(any<ByteArray>(), any<Int>(), any<Int>(), any())
            } answers {
                val buffer = firstArg<ByteArray>()
                if (!firstReadDone.getAndSet(true)) {
                    buffer.fill(0x01)
                    buffer.size
                } else {
                    readGate.await(10, TimeUnit.SECONDS)
                    buffer.fill(0x01)
                    buffer.size
                }
            }

            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val segmentDir = temporaryFolder.newFolder("rotation-race")
            capture.start(File(segmentDir, "segment-001.wav"))
            Thread.sleep(150)

            val barrier = CyclicBarrier(2)
            val results = ConcurrentHashMap<Int, SegmentRotationResult>()
            val contenders =
                (0 until 2).map { index ->
                    Thread {
                        barrier.await()
                        results[index] = capture.rotateSegment(File(segmentDir, "segment-00${index + 2}.wav"))
                    }.also { it.start() }
                }

            // The loser must fail fast at the single atomic entry check; only then may
            // the capture thread resume and consume the winner's pending target.
            val loserDeadline = System.currentTimeMillis() + 5_000
            while (results.isEmpty() && System.currentTimeMillis() < loserDeadline) {
                Thread.sleep(10)
            }
            readGate.countDown()
            contenders.forEach { it.join(10_000) }

            assertEquals(2, results.size)
            assertEquals(1, results.values.count { it == SegmentRotationResult.Success })
            val failure = results.values.filterIsInstance<SegmentRotationResult.Failed>().single()
            assertEquals("Segment rotation already pending", failure.reason)

            capture.stopAndFinalize()
        }

    @Test
    fun startThenStop_observesRoutingAndClearsSessionTokenOnRelease() =
        runTest {
            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val segment = File(temporaryFolder.root, "routing.wav")

            capture.start(segment)
            assertEquals(1L, capture.activeSessionToken)
            Thread.sleep(100)

            capture.stopAndFinalize()

            // MIC-013 listener symmetry: subscribe after a successful startRecording,
            // unsubscribe before the record is released.
            verifyOrder {
                inputDeviceSelector.observeRouting(audioRecord)
                inputDeviceSelector.stopObservingRouting(audioRecord)
                audioRecord.release()
            }
            verify { inputDeviceSelector.clearActiveDevice(1L) }
            assertNull(capture.activeSessionToken)
        }

    @Test
    fun releaseWithoutSave_stopsRoutingObservationAndClearsSessionToken() =
        runTest {
            val capture = GaplessWavSegmentCapture(inputDeviceSelector, sampleRate = 16_000)
            val segment = File(temporaryFolder.root, "discard.wav")
            capture.start(segment)
            Thread.sleep(100)

            capture.releaseWithoutSave()

            verifyOrder {
                inputDeviceSelector.stopObservingRouting(audioRecord)
                audioRecord.release()
            }
            verify { inputDeviceSelector.clearActiveDevice(1L) }
            assertNull(capture.activeSessionToken)
            assertFalse(segment.exists())
        }

    private companion object {
        const val QUICK_STOP_THRESHOLD_MS = 3_000L
        const val EXPECTED_READ_BUFFER_BYTES = 4_096L
    }
}
