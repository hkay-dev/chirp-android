package dev.chirpboard.app.di

import android.content.Context
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.llm.GOOGLE_CLOUD_VERTEX_PROVIDER_ID
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoffRequest
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoffResult
import dev.chirpboard.app.core.transcription.KeyboardDictationLiveCaptureRequest
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppKeyboardDictationHandoffTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun localRoute_keepsTheSourceUnderInlineOwnership() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("local-files")
            val source = sourceFile("local-cache", byteArrayOf(1, 2, 3, 4))
            val repository = mockk<RecordingRepository>(relaxed = true)
            val recovery = mockk<TranscriptionRecovery>(relaxed = true)
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.LOCAL_PARAKEET)

            val result = handoff.handoff(request(source))

            assertSame(KeyboardDictationHandoffResult.InlineLocal, result)
            assertTrue(source.isFile)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.readBytes())
            assertTrue(File(filesRoot, "recordings").listFiles().isNullOrEmpty())
            coVerify(exactly = 0) { repository.insert(any()) }
            coVerify(exactly = 0) { recovery.enqueue(any(), any()) }
        }

    @Test
    fun snapshottedLocalRoute_winsWhenTheLiveSettingChangesToCloud() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("snapshotted-local-files")
            val source = sourceFile("snapshotted-local-cache", byteArrayOf(1, 2, 3, 4))
            val repository = mockk<RecordingRepository>(relaxed = true)
            val recovery = mockk<TranscriptionRecovery>(relaxed = true)
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)

            val result =
                handoff.handoff(
                    request(
                        source = source,
                        transcriptionEngine = TranscriptionEngine.LOCAL_PARAKEET,
                    ),
                )

            assertSame(KeyboardDictationHandoffResult.InlineLocal, result)
            assertTrue(source.isFile)
            coVerify(exactly = 0) { repository.insert(any()) }
            coVerify(exactly = 0) { recovery.enqueue(any(), any()) }
        }

    @Test
    fun localLiveCapture_returnsAPathlessRouteSnapshot() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("local-live-files")
            val handoff =
                handoff(
                    filesRoot,
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    TranscriptionEngine.LOCAL_PARAKEET,
                )

            val capture =
                requireNotNull(
                    handoff.beginLiveCapture(
                        KeyboardDictationLiveCaptureRequest(
                            llmEnabled = true,
                            processingModeId = "proofread",
                            suppressHistory = false,
                        ),
                    ),
                )

            assertEquals(TranscriptionEngine.LOCAL_PARAKEET, capture.transcriptionEngine)
            assertNull(capture.recordingId)
            assertNull(capture.audioPath)
            assertFalse(File(filesRoot, "recordings").exists())
        }

    @Test
    fun localLiveCapture_withImeRouteSnapshotSkipsDataStore() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("snapshotted-local-live-files")
            val context =
                mockk<Context> {
                    every { filesDir } returns filesRoot
                    every { cacheDir } returns temporaryFolder.root
                }
            val routingStore =
                mockk<TranscriptionRoutingStore> {
                    every { selectedEngine } returns flowOf(TranscriptionEngine.LOCAL_PARAKEET)
                    coEvery { getSelectedEngine() } throws IOException("tap path must not read preferences")
                }
            val handoff =
                AppKeyboardDictationHandoff(
                    context = context,
                    recordingRepository = mockk(relaxed = true),
                    transcriptionRecovery = mockk(relaxed = true),
                    routingStore = routingStore,
                )

            val capture =
                requireNotNull(
                    handoff.beginLiveCapture(
                        KeyboardDictationLiveCaptureRequest(
                            llmEnabled = true,
                            processingModeId = "proofread",
                            suppressHistory = false,
                            transcriptionEngine = TranscriptionEngine.LOCAL_PARAKEET,
                        ),
                    ),
                )

            assertEquals(TranscriptionEngine.LOCAL_PARAKEET, capture.transcriptionEngine)
            assertNull(capture.audioPath)
            coVerify(exactly = 0) { routingStore.getSelectedEngine() }
        }

    @Test
    fun cloudRoute_movesExactAudioBeforeSavingThePendingRow() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("cloud-files")
            val bytes = byteArrayOf(4, 3, 2, 1, 0, 9, 8, 7)
            val source = sourceFile("cloud-cache", bytes)
            val repository = mockk<RecordingRepository>()
            val recovery = mockk<TranscriptionRecovery>()
            var insertedRecording: Recording? = null
            coEvery { repository.insert(any()) } coAnswers {
                firstArg<Recording>().also { recording ->
                    assertFalse(source.exists())
                    assertTrue(File(recording.audioPath).isFile)
                    assertArrayEquals(bytes, File(recording.audioPath).readBytes())
                    insertedRecording = recording
                }
            }
            coEvery { recovery.enqueue(any(), any()) } returns "keyboard-work"
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)

            val result =
                handoff.handoff(
                    request(
                        source = source,
                        sampleCount = 32_000L,
                        llmEnabled = true,
                        processingModeId = "email",
                    ),
                )

            val durable = result as KeyboardDictationHandoffResult.Durable
            val recording = checkNotNull(insertedRecording)
            assertEquals(recording.id, durable.recordingId)
            assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, recording.status)
            assertEquals(RecordingSource.KEYBOARD, recording.source)
            assertEquals(2_000L, recording.durationMs)
            assertEquals(TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id, recording.transcriptionEngineId)
            assertEquals("email", recording.requestedProcessingModeId)
            assertEquals(GOOGLE_CLOUD_VERTEX_PROVIDER_ID, recording.requestedLlmProviderId)
            assertNull(recording.requestedLlmModelId)
            assertTrue(recording.notifyWhenReady)
            assertTrue(recording.audioPath.startsWith(File(filesRoot, "recordings").absolutePath))
            assertTrue(
                File(filesRoot, "recordings")
                    .listFiles { file -> file.name.endsWith(".json") }
                    .isNullOrEmpty(),
            )
            coVerify(exactly = 1) {
                recovery.enqueue(recording.id, "keyboard-${recording.id}")
            }
        }

    @Test
    fun closedIme_forcesLocalCaptureIntoTheRecoverableQueue() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("forced-local-files")
            val source = sourceFile("forced-local-cache", ByteArray(VoiceRecorder.SAMPLE_RATE * Float.SIZE_BYTES))
            val repository = mockk<RecordingRepository>()
            val recovery = mockk<TranscriptionRecovery>()
            val inserted = slot<Recording>()
            coEvery { repository.insert(capture(inserted)) } returns Unit
            coEvery { recovery.enqueue(any(), any()) } returns "keyboard-work"
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.LOCAL_PARAKEET)

            val result =
                handoff.handoff(
                    request(
                        source = source,
                        transcriptionEngine = TranscriptionEngine.LOCAL_PARAKEET,
                        forceDurable = true,
                        llmEnabled = true,
                    ),
                )

            assertTrue(result is KeyboardDictationHandoffResult.Durable)
            assertEquals(TranscriptionEngine.LOCAL_PARAKEET.id, inserted.captured.transcriptionEngineId)
            assertEquals("proofread", inserted.captured.requestedProcessingModeId)
            assertNull(inserted.captured.requestedLlmProviderId)
            assertTrue(inserted.captured.notifyWhenReady)
            assertTrue(inserted.captured.enhancementRequestSnapshotted)
            coVerify(exactly = 1) { recovery.enqueue(inserted.captured.id, "keyboard-${inserted.captured.id}") }
        }

    @Test
    fun cloudLiveCapture_journalsAfterMicStartAndKeepsTheSameDurableAudio() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("live-files")
            val repository = mockk<RecordingRepository>()
            val recovery = mockk<TranscriptionRecovery>()
            val inserted = slot<Recording>()
            coEvery { repository.insert(capture(inserted)) } returns Unit
            coEvery { recovery.enqueue(any(), any()) } returns "keyboard-work"
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)

            val live =
                requireNotNull(
                    handoff.beginLiveCapture(
                        KeyboardDictationLiveCaptureRequest(
                            llmEnabled = true,
                            processingModeId = "email",
                            suppressHistory = false,
                        ),
                    ),
                )
            val audio = File(requireNotNull(live.audioPath))
            val marker = File(filesRoot, "recordings/.keyboard-live-${live.recordingId}.json")
            assertFalse(audio.exists())
            assertFalse(marker.exists())
            audio.parentFile?.mkdirs()
            audio.writeBytes(ByteArray(VoiceRecorder.SAMPLE_RATE * Float.SIZE_BYTES))
            handoff.markLiveCaptureStarted(live)
            assertTrue(marker.isFile)

            val result =
                handoff.handoff(
                    request(
                        source = audio,
                        sampleCount = VoiceRecorder.SAMPLE_RATE.toLong(),
                        llmEnabled = true,
                        processingModeId = "email",
                    ),
                )

            val durable = result as KeyboardDictationHandoffResult.Durable
            assertEquals(live.recordingId, durable.recordingId)
            assertEquals(live.recordingId, inserted.captured.id)
            assertEquals(audio.absolutePath, inserted.captured.audioPath)
            assertEquals(1_000L, inserted.captured.durationMs)
            assertTrue(audio.isFile)
            assertFalse(marker.exists())
        }

    @Test
    fun cloudLiveCapture_releaseForInlineKeepsAudioAndDropsTheUploadJournal() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("released-live-files")
            val handoff =
                handoff(
                    filesRoot,
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
                )
            val live =
                requireNotNull(
                    handoff.beginLiveCapture(
                        KeyboardDictationLiveCaptureRequest(
                            llmEnabled = true,
                            processingModeId = "email",
                            suppressHistory = false,
                        ),
                    ),
                )
            val audio = File(requireNotNull(live.audioPath)).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val marker = File(filesRoot, "recordings/.keyboard-live-${live.recordingId}.json")
            handoff.markLiveCaptureStarted(live)

            handoff.releaseLiveCaptureForInline(live)

            assertTrue(audio.isFile)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), audio.readBytes())
            assertFalse(marker.exists())
            assertEquals(0, handoff.recoverPendingHandoffs())
        }

    @Test
    fun cloudLiveCapture_routeReadFailureLeavesNoUnjournaledCapture() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("route-failure-files")
            val context =
                mockk<Context> {
                    every { filesDir } returns filesRoot
                    every { cacheDir } returns temporaryFolder.root
                }
            val routingStore =
                mockk<TranscriptionRoutingStore> {
                    every { selectedEngine } returns flowOf(TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)
                    coEvery { getSelectedEngine() } throws IOException("preferences unavailable")
                }
            val handoff =
                AppKeyboardDictationHandoff(
                    context = context,
                    recordingRepository = mockk(relaxed = true),
                    transcriptionRecovery = mockk(relaxed = true),
                    routingStore = routingStore,
                )

            try {
                handoff.beginLiveCapture(
                    KeyboardDictationLiveCaptureRequest(
                        llmEnabled = false,
                        processingModeId = "proofread",
                        suppressHistory = false,
                    ),
                )
                error("Expected route read failure")
            } catch (_: IOException) {
                Unit
            }

            assertFalse(File(filesRoot, "recordings").exists())
        }

    @Test
    fun killedLiveCapture_isRestoredFromItsFirstWrittenAudioBlocks() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("killed-live-files")
            val recordingsDirectory = File(filesRoot, "recordings").apply { mkdirs() }
            val recordingId = java.util.UUID.randomUUID()
            val audio =
                File(recordingsDirectory, "keyboard_$recordingId.f32pcm").apply {
                    writeBytes(ByteArray(VoiceRecorder.SAMPLE_RATE * Float.SIZE_BYTES + 3))
                }
            val marker = File(recordingsDirectory, ".keyboard-live-$recordingId.json")
            marker.writeText(
                com.google.gson.Gson().toJson(
                    PendingKeyboardLiveCapture(
                        recordingId = recordingId.toString(),
                        audioPath = audio.absolutePath,
                        ownerProcessId = "dead-process",
                        state = "recording",
                        createdAtEpochMs = 123_456L,
                        sampleRate = VoiceRecorder.SAMPLE_RATE,
                        llmEnabled = true,
                        processingModeId = "email",
                        notifyWhenReady = true,
                    ),
                ),
            )
            val inserted = slot<Recording>()
            val repository = mockk<RecordingRepository>()
            coEvery { repository.getRecording(recordingId) } returns null
            coEvery { repository.insert(capture(inserted)) } returns Unit
            val recovery = mockk<TranscriptionRecovery>(relaxed = true)
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)

            val recovered = handoff.recoverPendingHandoffs()

            assertEquals(1, recovered)
            assertFalse(marker.exists())
            assertTrue(audio.isFile)
            assertEquals(VoiceRecorder.SAMPLE_RATE * Float.SIZE_BYTES.toLong(), audio.length())
            assertEquals(recordingId, inserted.captured.id)
            assertEquals(1_000L, inserted.captured.durationMs)
            assertEquals(123_456L, inserted.captured.createdAt.time)
            assertEquals(TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id, inserted.captured.transcriptionEngineId)
            assertTrue(inserted.captured.notifyWhenReady)
        }

    @Test
    fun startupReplay_skipsTheCurrentProcessLiveCapture() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("current-live-files")
            val repository = mockk<RecordingRepository>(relaxed = true)
            val recovery = mockk<TranscriptionRecovery>(relaxed = true)
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)
            val live =
                requireNotNull(
                    handoff.beginLiveCapture(
                        KeyboardDictationLiveCaptureRequest(
                            llmEnabled = false,
                            processingModeId = "proofread",
                            suppressHistory = false,
                        ),
                    ),
                )
            File(requireNotNull(live.audioPath)).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(VoiceRecorder.SAMPLE_RATE * Float.SIZE_BYTES))
            }
            handoff.markLiveCaptureStarted(live)

            val recovered = handoff.recoverPendingHandoffs()

            assertEquals(0, recovered)
            assertTrue(File(filesRoot, "recordings/.keyboard-live-${live.recordingId}.json").isFile)
            coVerify(exactly = 0) { repository.insert(any()) }
        }

    @Test
    fun startupReplay_recoversAnOldCloudCaptureEvenWhenTheJournalNeverStarted() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("unjournaled-live-files")
            val recordingId = java.util.UUID.randomUUID()
            val audio =
                File(filesRoot, "recordings/keyboard_$recordingId.f32pcm").apply {
                    parentFile?.mkdirs()
                    writeBytes(ByteArray(VoiceRecorder.SAMPLE_RATE * Float.SIZE_BYTES))
                    setLastModified(System.currentTimeMillis() - 60_000L)
                }
            val inserted = slot<Recording>()
            val repository = mockk<RecordingRepository>()
            coEvery { repository.getRecording(recordingId) } returns null
            coEvery { repository.insert(capture(inserted)) } returns Unit
            val handoff =
                handoff(
                    filesRoot,
                    repository,
                    mockk(relaxed = true),
                    TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
                )

            val recovered = handoff.recoverPendingHandoffs()

            assertEquals(1, recovered)
            assertEquals(recordingId, inserted.captured.id)
            assertEquals(audio.absolutePath, inserted.captured.audioPath)
            assertTrue(inserted.captured.notifyWhenReady)
        }

    @Test
    fun interruptedHandoffJournal_restoresTheDurableRowBeforeQueueRecovery() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("recovery-files")
            val captureRoot = temporaryFolder.newFolder("recovery-cache")
            val captureDirectory = File(captureRoot, "keyboard-capture").apply { mkdirs() }
            val source = File(captureDirectory, "dictation-recovery.f32pcm").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            }
            val recordingId = java.util.UUID.randomUUID()
            val recordingsDirectory = File(filesRoot, "recordings").apply { mkdirs() }
            val destination = File(recordingsDirectory, "keyboard_$recordingId.f32pcm")
            val marker = File(recordingsDirectory, ".keyboard-handoff-$recordingId.json")
            marker.writeText(
                com.google.gson.Gson().toJson(
                    PendingKeyboardHandoff(
                        recordingId = recordingId.toString(),
                        sourcePath = source.absolutePath,
                        destinationPath = destination.absolutePath,
                        durationMs = 2_000L,
                        llmEnabled = true,
                        processingModeId = "email",
                        notifyWhenReady = true,
                    ),
                ),
            )
            val inserted = slot<Recording>()
            val repository = mockk<RecordingRepository>()
            coEvery { repository.getRecording(recordingId) } returns null
            coEvery { repository.insert(capture(inserted)) } returns Unit
            val recovery = mockk<TranscriptionRecovery>(relaxed = true)
            val handoff =
                handoff(
                    filesRoot = filesRoot,
                    cacheRoot = captureRoot,
                    repository = repository,
                    recovery = recovery,
                    engine = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
                )

            val recovered = handoff.recoverPendingHandoffs()

            assertEquals(1, recovered)
            assertFalse(source.exists())
            assertTrue(destination.isFile)
            assertFalse(marker.exists())
            assertEquals(recordingId, inserted.captured.id)
            assertEquals(destination.absolutePath, inserted.captured.audioPath)
            assertEquals("email", inserted.captured.requestedProcessingModeId)
            assertEquals(GOOGLE_CLOUD_VERTEX_PROVIDER_ID, inserted.captured.requestedLlmProviderId)
            assertTrue(inserted.captured.notifyWhenReady)
            coVerify(exactly = 0) { recovery.enqueue(any(), any()) }
        }

    @Test
    fun partialHandoffJournal_isPromotedAndRecovered() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("partial-recovery-files")
            val captureRoot = temporaryFolder.newFolder("partial-recovery-cache")
            val captureDirectory = File(captureRoot, "keyboard-capture").apply { mkdirs() }
            val source = File(captureDirectory, "dictation-partial.f32pcm").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val recordingId = java.util.UUID.randomUUID()
            val recordingsDirectory = File(filesRoot, "recordings").apply { mkdirs() }
            val destination = File(recordingsDirectory, "keyboard_$recordingId.f32pcm")
            val partial = File(recordingsDirectory, ".keyboard-handoff-$recordingId.json.partial")
            partial.writeText(
                com.google.gson.Gson().toJson(
                    PendingKeyboardHandoff(
                        recordingId = recordingId.toString(),
                        sourcePath = source.absolutePath,
                        destinationPath = destination.absolutePath,
                        durationMs = 1_000L,
                        llmEnabled = false,
                        processingModeId = "proofread",
                        notifyWhenReady = true,
                    ),
                ),
            )
            val inserted = slot<Recording>()
            val repository = mockk<RecordingRepository>()
            coEvery { repository.getRecording(recordingId) } returns null
            coEvery { repository.insert(capture(inserted)) } returns Unit
            val handoff =
                handoff(
                    filesRoot = filesRoot,
                    cacheRoot = captureRoot,
                    repository = repository,
                    recovery = mockk(relaxed = true),
                    engine = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
                )

            val recovered = handoff.recoverPendingHandoffs()

            assertEquals(1, recovered)
            assertFalse(partial.exists())
            assertFalse(source.exists())
            assertTrue(destination.isFile)
            assertEquals(recordingId, inserted.captured.id)
        }

    @Test
    fun discard_rereadsTheWorkerSwappedPathBeforeDeletingAudio() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("discard-files")
            val recordingId = java.util.UUID.randomUUID()
            val rawAudio = File(filesRoot, "keyboard_$recordingId.f32pcm").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val wavAudio = File(filesRoot, "keyboard_$recordingId.wav").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
            val original =
                Recording(
                    id = recordingId,
                    title = "Keyboard recording",
                    audioPath = rawAudio.absolutePath,
                    status = RecordingStatus.TRANSCRIBING,
                    source = RecordingSource.KEYBOARD,
                )
            val winning = original.copy(audioPath = wavAudio.absolutePath)
            val repository = mockk<RecordingRepository>()
            coEvery { repository.getRecording(recordingId) } returnsMany listOf(original, winning)
            coEvery { repository.deleteById(recordingId) } coAnswers {
                assertTrue(rawAudio.isFile)
                assertTrue(wavAudio.isFile)
            }
            val recovery = mockk<TranscriptionRecovery>(relaxed = true)
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)

            val discarded = handoff.discard(recordingId)

            assertTrue(discarded)
            assertFalse(rawAudio.exists())
            assertFalse(wavAudio.exists())
            coVerify(exactly = 1) { recovery.cancelProcessing(recordingId) }
            coVerify(exactly = 1) { repository.deleteById(recordingId) }
        }

    @Test
    fun discard_reportsAFileCleanupFailure() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("discard-failure-files")
            val recordingId = java.util.UUID.randomUUID()
            val undeletableAudio = File(filesRoot, "non-empty-audio").apply {
                mkdirs()
                resolve("child").writeText("still here")
            }
            val recording =
                Recording(
                    id = recordingId,
                    title = "Keyboard recording",
                    audioPath = undeletableAudio.absolutePath,
                    status = RecordingStatus.TRANSCRIBING,
                    source = RecordingSource.KEYBOARD,
                )
            val repository = mockk<RecordingRepository>()
            coEvery { repository.getRecording(recordingId) } returns recording
            coEvery { repository.deleteById(recordingId) } returns Unit
            val handoff =
                handoff(
                    filesRoot,
                    repository,
                    mockk(relaxed = true),
                    TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
                )

            val discarded = handoff.discard(recordingId)

            assertFalse(discarded)
            assertTrue(undeletableAudio.exists())
            coVerify(exactly = 1) { repository.deleteById(recordingId) }
        }

    @Test
    fun enqueueFailure_keepsTheDurableRowRecoverable() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("enqueue-files")
            val source = sourceFile("enqueue-cache", byteArrayOf(1, 0, 1, 0))
            val repository = mockk<RecordingRepository>()
            val recovery = mockk<TranscriptionRecovery>()
            val inserted = slot<Recording>()
            val enqueueFailure = IOException("work manager unavailable")
            coEvery { repository.insert(capture(inserted)) } returns Unit
            coEvery { recovery.enqueue(any(), any()) } throws enqueueFailure
            coEvery { recovery.markPendingForQueueRecovery(any(), any(), any()) } returns Unit
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)

            val result = handoff.handoff(request(source))

            val durable = result as KeyboardDictationHandoffResult.Durable
            assertEquals(inserted.captured.id, durable.recordingId)
            assertTrue(File(inserted.captured.audioPath).isFile)
            coVerify(exactly = 1) {
                recovery.markPendingForQueueRecovery(
                    recordingId = inserted.captured.id,
                    reason = "keyboard durable handoff enqueue failed",
                    cause = enqueueFailure,
                )
            }
        }

    @Test
    fun insertFailure_rollsTheExactAudioBackToTheInlineSource() =
        runTest {
            val filesRoot = temporaryFolder.newFolder("rollback-files")
            val bytes = byteArrayOf(7, 6, 5, 4, 3, 2, 1, 0)
            val source = sourceFile("rollback-cache", bytes)
            val repository = mockk<RecordingRepository>()
            val recovery = mockk<TranscriptionRecovery>(relaxed = true)
            coEvery { repository.insert(any()) } throws IOException("database unavailable")
            val handoff = handoff(filesRoot, repository, recovery, TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3)

            val result = handoff.handoff(request(source))

            val failed = result as KeyboardDictationHandoffResult.Failed
            assertTrue(failed.sourceAvailableForInlineFallback)
            assertTrue(source.isFile)
            assertArrayEquals(bytes, source.readBytes())
            assertTrue(File(filesRoot, "recordings").listFiles().isNullOrEmpty())
            coVerify(exactly = 0) { recovery.enqueue(any(), any()) }
        }

    @Test
    fun insertRollback_acceptsAnOriginalSourceLeftByTheCopyFallback() {
        val bytes = byteArrayOf(2, 4, 6, 8)
        val source = sourceFile("copy-fallback-source", bytes)
        val duplicate = sourceFile("copy-fallback-durable", bytes)

        val restored = restoreSourceAfterInsertFailure(source, duplicate)

        assertTrue(restored)
        assertTrue(source.isFile)
        assertArrayEquals(bytes, source.readBytes())
        assertFalse(duplicate.exists())
    }

    private fun handoff(
        filesRoot: File,
        repository: RecordingRepository,
        recovery: TranscriptionRecovery,
        engine: TranscriptionEngine,
        cacheRoot: File = temporaryFolder.root,
    ): AppKeyboardDictationHandoff {
        val context =
            mockk<Context> {
                every { filesDir } returns filesRoot
                every { cacheDir } returns cacheRoot
            }
        val routingStore =
            mockk<TranscriptionRoutingStore> {
                every { selectedEngine } returns flowOf(engine)
                coEvery { getSelectedEngine() } returns engine
            }
        return AppKeyboardDictationHandoff(
            context = context,
            recordingRepository = repository,
            transcriptionRecovery = recovery,
            routingStore = routingStore,
        )
    }

    private fun sourceFile(
        directoryName: String,
        bytes: ByteArray,
    ): File =
        File(temporaryFolder.newFolder(directoryName), "capture.f32pcm").apply {
            writeBytes(bytes)
        }

    private fun request(
        source: File,
        sampleCount: Long = 16_000L,
        llmEnabled: Boolean = false,
        processingModeId: String = "proofread",
        transcriptionEngine: TranscriptionEngine? = null,
        forceDurable: Boolean = false,
    ) =
        KeyboardDictationHandoffRequest(
            audioSource =
                InlineAudioSource.PcmFloatFile(
                    path = source.absolutePath,
                    sampleCount = sampleCount,
                ),
            llmEnabled = llmEnabled,
            processingModeId = processingModeId,
            transcriptionEngine = transcriptionEngine,
            forceDurable = forceDurable,
        )
}
