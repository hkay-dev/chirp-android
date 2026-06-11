package dev.chirpboard.app.di

import android.content.Context
import android.util.Log
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.core.export.TranscriptExportPort
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.transcription.InlineAudioSource
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
            )

            assertTrue(File(root, "recordings").listFiles().isNullOrEmpty())
            verify(exactly = 0) { audioEncoder.encode(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { recordingRepository.createRecordingWithTranscript(any(), any(), any()) }
            coVerify(exactly = 0) { transcriptExportPort.exportIfEnabled(any(), any(), any()) }
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
            )
            persistence.discardSamples()

            assertFalse(explicitFile.exists())
            assertFalse(pendingFile.exists())
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
            coEvery { exportIfEnabled(any(), any(), any()) } returns Result.success(Unit)
        }
}
