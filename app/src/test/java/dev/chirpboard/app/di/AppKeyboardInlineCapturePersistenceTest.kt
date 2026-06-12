package dev.chirpboard.app.di

import android.content.Context
import android.util.Log
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.core.export.TranscriptExportOutcome
import dev.chirpboard.app.core.export.TranscriptExportPort
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppKeyboardInlineCapturePersistenceTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun persist_whenSaveEnabled_returnsAfterFileAndDatabaseWrite() =
        runTest {
            val root = createTempDir("keyboard-persist-enabled")
            val audioEncoder = audioEncoderWritingFile()
            val recordingRepository = mockk<RecordingRepository>()
            val savedRecording = CompletableDeferred<Recording>()
            coEvery {
                recordingRepository.createRecordingWithTranscript(any(), any(), any())
            } answers {
                firstArg<Recording>().also { savedRecording.complete(it) }
            }
            val transcriptExportPort = transcriptExportPort()
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    saveRecordings = true,
                    transcriptExportPort = transcriptExportPort,
                )

            persistence.persist(
                samples = floatArrayOf(0.1f, 0.2f, 0.3f),
                rawText = "hello",
                processedText = "Hello",
                errorMessage = null,
                reason = InlineCapturePersistReason.COMPLETED,
            )

            val recording = savedRecording.await()
            assertEquals(RecordingSource.KEYBOARD, recording.source)
            assertTrue(File(recording.audioPath).exists())
            coVerify {
                recordingRepository.createRecordingWithTranscript(
                    match { it.source == RecordingSource.KEYBOARD },
                    match<Transcript> { it.rawText == "hello" && it.processedText == "Hello" },
                    emptyList(),
                )
            }
            coVerify {
                transcriptExportPort.exportIfEnabled(
                    match { it.title == "hello" && it.sourceName == "keyboard" },
                    "Hello",
                    null,
                )
            }
        }

    @Test
    fun persist_whenSaveDisabled_readsPreferenceAndDoesNotCreateFileOrRecording() =
        runTest {
            val root = createTempDir("keyboard-persist-disabled")
            val audioEncoder = mockk<AudioEncoder>(relaxed = true)
            val recordingRepository = mockk<RecordingRepository>(relaxed = true)
            val transcriptExportPort = transcriptExportPort()
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    saveRecordings = false,
                    transcriptExportPort = transcriptExportPort,
                )

            persistence.persist(
                samples = floatArrayOf(0.1f),
                rawText = "hello",
                processedText = null,
                errorMessage = null,
                reason = InlineCapturePersistReason.COMPLETED,
            )

            assertTrue(File(root, "recordings").listFiles().isNullOrEmpty())
            verify(exactly = 0) { audioEncoder.encode(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { recordingRepository.createRecordingWithTranscript(any(), any(), any()) }
            coVerify(exactly = 0) { transcriptExportPort.exportIfEnabled(any(), any(), any()) }
        }

    @Test
    fun persist_rescueReasonSavesEvenWhenSaveDisabled() =
        runTest {
            val root = createTempDir("keyboard-persist-rescue-reason")
            val audioEncoder = audioEncoderWritingFile()
            val recordingRepository = mockk<RecordingRepository>()
            val savedRecording = CompletableDeferred<Recording>()
            coEvery {
                recordingRepository.createRecordingWithTranscript(any(), any(), any())
            } answers {
                firstArg<Recording>().also { savedRecording.complete(it) }
            }
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    saveRecordings = false,
                    transcriptExportPort = transcriptExportPort(),
                )

            // A rescue entry is an error artifact, not a normal keyboard recording: it must
            // be saved even with saveKeyboardRecordings off so the transcript is retrievable.
            persistence.persist(
                samples = floatArrayOf(0.1f, 0.2f),
                rawText = "rescued dictation",
                processedText = null,
                errorMessage = "Couldn't insert dictated text into the field",
                reason = InlineCapturePersistReason.RESCUE,
            )

            val recording = savedRecording.await()
            assertEquals(RecordingSource.KEYBOARD, recording.source)
            assertEquals("Couldn't insert dictated text into the field", recording.errorMessage)
            assertTrue(File(recording.audioPath).exists())
            coVerify {
                recordingRepository.createRecordingWithTranscript(
                    match { it.errorMessage == "Couldn't insert dictated text into the field" },
                    match<Transcript> { it.rawText == "rescued dictation" },
                    emptyList(),
                )
            }
        }

    @Test
    fun persist_userCancelledRespectsSaveDisabledAndRetainsNothing() =
        runTest {
            val root = createTempDir("keyboard-persist-user-cancelled")
            val audioEncoder = mockk<AudioEncoder>(relaxed = true)
            val recordingRepository = mockk<RecordingRepository>(relaxed = true)
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    saveRecordings = false,
                    transcriptExportPort = transcriptExportPort(),
                )
            val sourceFile = File(root, "cancelled.f32pcm").apply { writeText("cancelled") }

            // An explicit user cancel carries an error message but is NOT a rescue:
            // with saving off, neither audio nor partial text may be retained.
            persistence.persistAudioSource(
                audioSource =
                    InlineAudioSource.PcmFloatFile(
                        path = sourceFile.absolutePath,
                        sampleCount = 1,
                    ),
                rawText = "partial dictation",
                processedText = null,
                errorMessage = "Dictation cancelled",
                reason = InlineCapturePersistReason.USER_CANCELLED,
            )

            assertFalse(sourceFile.exists())
            assertTrue(File(root, "recordings").listFiles().isNullOrEmpty())
            coVerify(exactly = 0) { recordingRepository.createRecordingWithTranscript(any(), any(), any()) }
            coVerify(exactly = 0) { recordingRepository.insert(any()) }
        }

    @Test
    fun persist_whenEncodingFailsStillSavesTranscriptWithoutAudio() =
        runTest {
            val root = createTempDir("keyboard-persist-encode-fails")
            val audioEncoder =
                mockk<AudioEncoder> {
                    every { encode(any(), any(), any(), any(), any()) } returns false
                }
            val recordingRepository = mockk<RecordingRepository>()
            val savedRecording = CompletableDeferred<Recording>()
            coEvery {
                recordingRepository.createRecordingWithTranscript(any(), any(), any())
            } answers {
                firstArg<Recording>().also { savedRecording.complete(it) }
            }
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    saveRecordings = true,
                    transcriptExportPort = transcriptExportPort(),
                )

            // The transcript is already in hand; an encoder failure (disk full, codec
            // error) must not drop it. A text-only entry is saved instead.
            persistence.persist(
                samples = floatArrayOf(0.1f, 0.2f),
                rawText = "rescued dictation",
                processedText = null,
                errorMessage = "Couldn't insert dictated text into the field",
                reason = InlineCapturePersistReason.RESCUE,
            )

            val recording = savedRecording.await()
            assertEquals("", recording.audioPath)
            assertTrue(File(root, "recordings").listFiles().isNullOrEmpty())
            coVerify {
                recordingRepository.createRecordingWithTranscript(
                    match { it.audioPath.isEmpty() },
                    match<Transcript> { it.rawText == "rescued dictation" },
                    emptyList(),
                )
            }
        }

    @Test
    fun persist_whenEncoderThrowsStillSavesTranscriptWithoutAudio() =
        runTest {
            val root = createTempDir("keyboard-persist-encode-throws")
            val audioEncoder =
                mockk<AudioEncoder> {
                    every { encode(any(), any(), any(), any(), any()) } throws IllegalStateException("codec crashed")
                }
            val recordingRepository = mockk<RecordingRepository>()
            val savedRecording = CompletableDeferred<Recording>()
            coEvery {
                recordingRepository.createRecordingWithTranscript(any(), any(), any())
            } answers {
                firstArg<Recording>().also { savedRecording.complete(it) }
            }
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    saveRecordings = true,
                    transcriptExportPort = transcriptExportPort(),
                )

            persistence.persist(
                samples = floatArrayOf(0.1f, 0.2f),
                rawText = "rescued dictation",
                processedText = null,
                errorMessage = "Couldn't insert dictated text into the field",
                reason = InlineCapturePersistReason.RESCUE,
            )

            val recording = savedRecording.await()
            assertEquals("", recording.audioPath)
            coVerify {
                recordingRepository.createRecordingWithTranscript(
                    match { it.audioPath.isEmpty() },
                    match<Transcript> { it.rawText == "rescued dictation" },
                    emptyList(),
                )
            }
        }

    @Test
    fun persist_whenEncodingFailsWithoutTranscriptSavesNothing() =
        runTest {
            val root = createTempDir("keyboard-persist-encode-fails-no-text")
            val audioEncoder =
                mockk<AudioEncoder> {
                    every { encode(any(), any(), any(), any(), any()) } returns false
                }
            val recordingRepository = mockk<RecordingRepository>(relaxed = true)
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    saveRecordings = true,
                    transcriptExportPort = transcriptExportPort(),
                )

            persistence.persist(
                samples = floatArrayOf(0.1f, 0.2f),
                rawText = null,
                processedText = null,
                errorMessage = "Dictation stop timed out",
                reason = InlineCapturePersistReason.RESCUE,
            )

            assertTrue(File(root, "recordings").listFiles().isNullOrEmpty())
            coVerify(exactly = 0) { recordingRepository.createRecordingWithTranscript(any(), any(), any()) }
            coVerify(exactly = 0) { recordingRepository.insert(any()) }
        }

    @Test
    fun persist_cancelledAfterStartStillCompletesLocalSave() =
        runTest {
            val root = createTempDir("keyboard-persist-cancelled")
            val encodeStarted = CompletableDeferred<Unit>()
            val audioEncoder =
                mockk<AudioEncoder> {
                    every { encode(any(), any(), any(), any(), any()) } answers {
                        encodeStarted.complete(Unit)
                        Thread.sleep(100L)
                        File(thirdArg<String>()).writeText("audio")
                        true
                    }
                }
            val recordingRepository = mockk<RecordingRepository>()
            val savedRecording = CompletableDeferred<Recording>()
            coEvery {
                recordingRepository.createRecordingWithTranscript(any(), any(), any())
            } answers {
                firstArg<Recording>().also { savedRecording.complete(it) }
            }
            val transcriptExportPort = transcriptExportPort()
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    saveRecordings = true,
                    transcriptExportPort = transcriptExportPort,
                )

            val job =
                launch {
                    persistence.persist(
                        samples = floatArrayOf(0.1f, 0.2f, 0.3f),
                        rawText = "hello",
                        processedText = null,
                        errorMessage = null,
                        reason = InlineCapturePersistReason.COMPLETED,
                    )
                }
            encodeStarted.await()
            job.cancel()
            job.join()

            val recording = savedRecording.await()
            assertTrue(File(recording.audioPath).exists())
            coVerify { recordingRepository.createRecordingWithTranscript(any(), any(), any()) }
        }


    @Test
    fun persistAudioSource_withExplicitSourceDoesNotClearNewerPendingSource() =
        runTest {
            val root = createTempDir("keyboard-persist-explicit-source")
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = mockk(relaxed = true),
                    recordingRepository = mockk(relaxed = true),
                    saveRecordings = false,
                    transcriptExportPort = transcriptExportPort(),
                )
            val explicitFile = File(root, "explicit.f32pcm").apply { writeText("explicit") }
            val pendingFile = File(root, "pending.f32pcm").apply { writeText("pending") }
            persistence.prepareAudioSource(
                InlineAudioSource.PcmFloatFile(
                    path = pendingFile.absolutePath,
                    sampleCount = 1,
                ),
            )

            persistence.persistAudioSource(
                audioSource =
                    InlineAudioSource.PcmFloatFile(
                        path = explicitFile.absolutePath,
                        sampleCount = 1,
                    ),
                rawText = null,
                processedText = null,
                errorMessage = "cancelled",
                reason = InlineCapturePersistReason.USER_CANCELLED,
            )
            persistence.discardSamples()

            assertFalse(explicitFile.exists())
            assertFalse(pendingFile.exists())
        }

    @Test
    fun releasePendingAudioSource_keepsBackingFileForDetachedPipeline() =
        runTest {
            val root = createTempDir("keyboard-release-pending")
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = mockk(relaxed = true),
                    recordingRepository = mockk(relaxed = true),
                    saveRecordings = false,
                    transcriptExportPort = transcriptExportPort(),
                )
            val detachedFile = File(root, "detached.f32pcm").apply { writeText("detached") }
            persistence.prepareAudioSource(
                InlineAudioSource.PcmFloatFile(
                    path = detachedFile.absolutePath,
                    sampleCount = 1,
                ),
            )

            // Ownership handoff: the detached pipeline still needs the backing file.
            persistence.releasePendingAudioSource()

            assertTrue(detachedFile.exists())
            // The staged reference is gone, so a later discardSamples (e.g. from the
            // next dictation's stop path) cannot delete the detached pipeline's audio.
            persistence.discardSamples()
            assertTrue(detachedFile.exists())
        }

    @Test
    fun discardAudioSource_deletesOnlyThatSourceAndKeepsNewerPendingSource() =
        runTest {
            val root = createTempDir("keyboard-discard-explicit-source")
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = mockk(relaxed = true),
                    recordingRepository = mockk(relaxed = true),
                    saveRecordings = false,
                    transcriptExportPort = transcriptExportPort(),
                )
            val detachedFile = File(root, "detached.f32pcm").apply { writeText("detached") }
            val pendingFile = File(root, "pending.f32pcm").apply { writeText("pending") }
            persistence.prepareAudioSource(
                InlineAudioSource.PcmFloatFile(
                    path = pendingFile.absolutePath,
                    sampleCount = 1,
                ),
            )

            persistence.discardAudioSource(
                InlineAudioSource.PcmFloatFile(
                    path = detachedFile.absolutePath,
                    sampleCount = 1,
                ),
            )

            assertFalse(detachedFile.exists())
            assertTrue(pendingFile.exists())
            // The newer staged source is still owned by the persistence layer.
            persistence.discardSamples()
            assertFalse(pendingFile.exists())
        }

    @Test
    fun discardAudioSource_clearsPendingReferenceWhenDiscardingStagedSource() =
        runTest {
            val root = createTempDir("keyboard-discard-staged-source")
            val persistence =
                persistence(
                    root = root,
                    audioEncoder = mockk(relaxed = true),
                    recordingRepository = mockk(relaxed = true),
                    saveRecordings = false,
                    transcriptExportPort = transcriptExportPort(),
                )
            val stagedFile = File(root, "staged.f32pcm").apply { writeText("staged") }
            val stagedSource =
                InlineAudioSource.PcmFloatFile(
                    path = stagedFile.absolutePath,
                    sampleCount = 1,
                )
            persistence.prepareAudioSource(stagedSource)

            persistence.discardAudioSource(stagedSource)

            assertFalse(stagedFile.exists())
        }

    @Test
    fun persistAudioSource_discardsFileBackedSourceWhenPreferenceReadFails() =
        runTest {
            val root = createTempDir("keyboard-persist-throwing-preference")
            val sourceFile = File(root, "source.f32pcm").apply { writeText("source") }
            val keyboardPreferences =
                mockk<KeyboardPreferences> {
                    every { saveKeyboardRecordings } returns flow { throw IllegalStateException("boom") }
                    every { recordingQualityPreset } returns flowOf(RecordingQualityPreset.High)
                    every { outputFormat } returns flowOf(RecordingOutputFormat.WAV)
                }
            val persistence =
                AppKeyboardInlineCapturePersistence(
                    context =
                        mockk {
                            every { filesDir } returns root
                        },
                    recordingRepository = mockk(relaxed = true),
                    keyboardPreferences = keyboardPreferences,
                    transcriptExportPort = transcriptExportPort(),
                    audioEncoder = mockk(relaxed = true),
                )

            var failed = false
            try {
                persistence.persistAudioSource(
                    audioSource =
                        InlineAudioSource.PcmFloatFile(
                            path = sourceFile.absolutePath,
                            sampleCount = 1,
                        ),
                    rawText = null,
                    processedText = null,
                    errorMessage = null,
                    reason = InlineCapturePersistReason.COMPLETED,
                )
            } catch (e: IllegalStateException) {
                failed = true
            }

            assertTrue(failed)
            assertFalse(sourceFile.exists())
        }
    private fun audioEncoderWritingFile(): AudioEncoder =
        mockk {
            every { encode(any(), any(), any(), any(), any()) } answers {
                File(thirdArg<String>()).writeText("audio")
                true
            }
        }

    private fun persistence(
        root: File,
        audioEncoder: AudioEncoder,
        recordingRepository: RecordingRepository,
        saveRecordings: Boolean,
        transcriptExportPort: TranscriptExportPort,
    ): AppKeyboardInlineCapturePersistence {
        val context =
            mockk<Context> {
                every { filesDir } returns root
            }
        val keyboardPreferences =
            mockk<KeyboardPreferences> {
                every { saveKeyboardRecordings } returns flowOf(saveRecordings)
                every { recordingQualityPreset } returns flowOf(RecordingQualityPreset.High)
                every { outputFormat } returns flowOf(RecordingOutputFormat.WAV)
            }
        return AppKeyboardInlineCapturePersistence(
            context = context,
            recordingRepository = recordingRepository,
            keyboardPreferences = keyboardPreferences,
            transcriptExportPort = transcriptExportPort,
            audioEncoder = audioEncoder,
        )
    }

    private fun transcriptExportPort(): TranscriptExportPort =
        mockk {
            coEvery {
                exportIfEnabled(any(), any(), any(), any(), any())
            } returns Result.success(TranscriptExportOutcome(exportedUri = null))
        }
}
