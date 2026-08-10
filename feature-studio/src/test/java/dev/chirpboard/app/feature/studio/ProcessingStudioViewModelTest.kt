package dev.chirpboard.app.feature.studio

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.core.playback.RecordingPlaybackController
import dev.chirpboard.app.core.playback.RecordingPlaybackState
import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.RecoveryOwnershipState
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.toUserMessage
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import dev.chirpboard.app.data.repository.WordReplacementRepository
import android.util.Log
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.TranscriptPassageAction
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingStudioViewModelTest {
    private lateinit var repository: RecordingRepository
    private lateinit var transcriptionRecovery: TranscriptionRecovery
    private lateinit var context: Context

    @Before
    fun setup() {
        // feature-studio has no test-support dependency; stub Log inline so failure-path
        // logging (deleteRecording, selection actions) does not abort JVM tests.
        mockkStatic(Log::class)
        every { Log.v(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        repository = mockk(relaxed = true)
        transcriptionRecovery = mockk(relaxed = true)
        coEvery { transcriptionRecovery.getRecoveryDiagnostics(any()) } returns
            RecoveryDiagnostics(
                latestReason = null,
                lastAttemptEpochMs = null,
                ownership = RecoveryOwnershipState.MISSING_OR_TERMINAL,
            )
        context = mockk(relaxed = true)
        // I18N-08: snackbar copy moved to resources; resolve the ids these tests assert.
        every { context.getString(dev.chirpboard.app.core.ui.R.string.rec_msg_requeued_transcription) } returns
            "Re-queued for transcription"
        every { context.getString(dev.chirpboard.app.core.ui.R.string.rec_msg_transcription_cancelled) } returns
            "Transcription cancelled"
        every { context.getString(dev.chirpboard.app.core.ui.R.string.rec_msg_delete_failed) } returns
            "Couldn't delete the recording"
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `invalid recording id marks InvalidId without subscribing`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val viewModel = createViewModel(recordingId = "not-a-uuid")
            advanceUntilIdle()

            assertEquals(ProcessingStudioLoadState.InvalidId, viewModel.uiState.value.loadState)
        }

    @Test
    fun `missing recording row transitions to NotFound after grace period`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            stubEmptyRecordingFlows(recordingId)
            coEvery { repository.getRecording(recordingId) } returns null

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()
            advanceTimeBy(MISSING_RECORDING_GRACE_MS)
            advanceUntilIdle()

            assertEquals(ProcessingStudioLoadState.NotFound, viewModel.uiState.value.loadState)
        }

    @Test
    fun `opening studio for different recording pauses active mini player`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingA = UUID.randomUUID()
            val recordingB = UUID.randomUUID()
            val playbackState =
                MutableStateFlow(
                    RecordingPlaybackState(
                        recordingId = recordingA,
                        title = "Recording A",
                        audioPath = "/tmp/a.m4a",
                        isPlaying = true,
                    ),
                )
            val playbackController =
                mockk<RecordingPlaybackController>(relaxed = true) {
                    every { state } returns playbackState
                }
            stubEmptyRecordingFlows(recordingB)

            createViewModel(recordingId = recordingB.toString(), playbackController = playbackController)
            advanceUntilIdle()

            verify { playbackController.pauseIfDifferentRecording(recordingB) }
        }

    @Test
    fun `deleted recording while observing transitions to NotFound immediately`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val recording = sampleRecording(recordingId)
            val recordingFlow = MutableStateFlow<RepositoryFlowState<Recording?>>(RepositoryFlowState(recording))
            every { repository.getRecordingFlow(recordingId) } returns recordingFlow
            stubSupportingFlows(recordingId)

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()
            assertEquals(ProcessingStudioLoadState.Ready, viewModel.uiState.value.loadState)

            recordingFlow.value = RepositoryFlowState(null)
            advanceUntilIdle()

            assertEquals(ProcessingStudioLoadState.NotFound, viewModel.uiState.value.loadState)
        }

    @Test
    fun `pending enhancement shows enhancement recovery and uses pending enhancement recovery`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val recording = sampleRecording(recordingId).copy(status = RecordingStatus.PENDING_ENHANCEMENT)
            every { repository.getRecordingFlow(recordingId) } returns flowOf(RepositoryFlowState(recording))
            stubSupportingFlows(recordingId)
            coEvery { transcriptionRecovery.recoverPendingEnhancement(recordingId) } returns ManualRecoveryResult.ENQUEUED

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.recoveryActions.showEnhancementRecovery)
            assertFalse(viewModel.uiState.value.recoveryActions.showRetranscribeFromEnhancing)

            viewModel.recoverEnhancing()
            advanceUntilIdle()

            coVerify { transcriptionRecovery.recoverPendingEnhancement(recordingId) }
        }

    @Test
    fun `retry transcription reports requeue when retry is enqueued`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val recording = sampleRecording(recordingId).copy(status = RecordingStatus.FAILED)
            every { repository.getRecordingFlow(recordingId) } returns flowOf(RepositoryFlowState(recording))
            stubSupportingFlows(recordingId)
            coEvery { transcriptionRecovery.retry(recordingId) } returns ManualRecoveryResult.ENQUEUED

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            viewModel.retryTranscription()
            advanceUntilIdle()

            coVerify { transcriptionRecovery.retry(recordingId) }
            assertEquals("Re-queued for transcription", viewModel.message.value)
        }

    @Test
    fun `retry transcription surfaces honest message when recording is no longer failed`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val recording = sampleRecording(recordingId)
            every { repository.getRecordingFlow(recordingId) } returns flowOf(RepositoryFlowState(recording))
            stubSupportingFlows(recordingId)
            coEvery { transcriptionRecovery.retry(recordingId) } returns ManualRecoveryResult.NOT_RECOVERABLE_STATE

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            viewModel.retryTranscription()
            advanceUntilIdle()

            coVerify { transcriptionRecovery.retry(recordingId) }
            assertEquals(
                ManualRecoveryResult.NOT_RECOVERABLE_STATE.toUserMessage(context, "Re-queued for transcription"),
                viewModel.message.value,
            )
        }

    @Test
    fun `same status error changes refresh recovery diagnostics`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val recordingFlow =
                MutableStateFlow<RepositoryFlowState<Recording?>>(
                    RepositoryFlowState(
                        sampleRecording(recordingId)
                            .copy(status = RecordingStatus.FAILED, errorMessage = "first failure"),
                    ),
                )
            every { repository.getRecordingFlow(recordingId) } returns recordingFlow
            stubSupportingFlows(recordingId)
            coEvery { transcriptionRecovery.getRecoveryDiagnostics(recordingId) } returnsMany
                listOf(
                    RecoveryDiagnostics(
                        latestReason = "first failure",
                        lastAttemptEpochMs = null,
                        ownership = RecoveryOwnershipState.MISSING_OR_TERMINAL,
                    ),
                    RecoveryDiagnostics(
                        latestReason = "second failure",
                        lastAttemptEpochMs = null,
                        ownership = RecoveryOwnershipState.MISSING_OR_TERMINAL,
                    ),
                )

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()
            assertEquals("first failure", viewModel.uiState.value.recoveryDiagnostics.latestReason)

            recordingFlow.value =
                RepositoryFlowState(
                    sampleRecording(recordingId)
                        .copy(status = RecordingStatus.FAILED, errorMessage = "second failure"),
                )
            advanceUntilIdle()

            assertEquals("second failure", viewModel.uiState.value.recoveryDiagnostics.latestReason)
        }

    @Test
    fun `late diagnostics patch recovery fields without replacing newer transcript`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val transcriptFlow =
                MutableStateFlow<RepositoryFlowState<Transcript?>>(
                    RepositoryFlowState(
                        sampleTranscript(recordingId, rawText = "old transcript"),
                    ),
                )
            val diagnostics = CompletableDeferred<RecoveryDiagnostics>()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId).copy(status = RecordingStatus.FAILED)))
            every { repository.getTranscriptFlow(recordingId) } returns transcriptFlow
            every { repository.getTranscriptTimingsFlow(recordingId) } returns flowOf(RepositoryFlowState(emptyList()))
            every { repository.getStructuredOutcomeSnapshotFlow(recordingId) } returns flowOf(RepositoryFlowState(null))
            coEvery { transcriptionRecovery.getRecoveryDiagnostics(recordingId) } coAnswers { diagnostics.await() }

            val viewModel = createViewModel(recordingId = recordingId.toString())
            runCurrent()
            assertEquals("old transcript", viewModel.uiState.value.effectiveTranscriptText)

            transcriptFlow.value =
                RepositoryFlowState(
                    sampleTranscript(recordingId, rawText = "new transcript"),
                )
            runCurrent()
            diagnostics.complete(
                RecoveryDiagnostics(
                    latestReason = "diagnostic finished",
                    lastAttemptEpochMs = null,
                    ownership = RecoveryOwnershipState.MISSING_OR_TERMINAL,
                ),
            )
            advanceUntilIdle()

            assertEquals("new transcript", viewModel.uiState.value.effectiveTranscriptText)
            assertEquals("diagnostic finished", viewModel.uiState.value.recoveryDiagnostics.latestReason)
        }

    @Test
    fun `playback reveal schedules once for repeated emissions of same recording and audio path`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val transcriptFlow =
                MutableStateFlow<RepositoryFlowState<Transcript?>>(
                    RepositoryFlowState(
                        sampleTranscript(recordingId, rawText = "first transcript"),
                    ),
                )
            val playbackController =
                mockk<RecordingPlaybackController>(relaxed = true) {
                    every { state } returns MutableStateFlow(RecordingPlaybackState())
                }
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            every { repository.getTranscriptFlow(recordingId) } returns transcriptFlow
            every { repository.getTranscriptTimingsFlow(recordingId) } returns flowOf(RepositoryFlowState(emptyList()))
            every { repository.getStructuredOutcomeSnapshotFlow(recordingId) } returns flowOf(RepositoryFlowState(null))

            createViewModel(recordingId = recordingId.toString(), playbackController = playbackController)
            runCurrent()
            transcriptFlow.value = RepositoryFlowState(sampleTranscript(recordingId, rawText = "second transcript"))
            runCurrent()
            transcriptFlow.value = RepositoryFlowState(sampleTranscript(recordingId, rawText = "third transcript"))
            runCurrent()
            advanceTimeBy(ChirpMotion.RECORD_HANDOFF_MS)
            runCurrent()

            verify(exactly = 1) {
                playbackController.onStudioOpened(recordingId, "Meeting", "/tmp/test.m4a")
            }
        }

    @Test
    fun `raw cloud capture never reaches playback or audio sharing`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val rawRecording =
                sampleRecording(recordingId).copy(
                    audioPath = "/tmp/cloud-dictation.f32pcm",
                    status = RecordingStatus.TRANSCRIBING,
                    source = RecordingSource.KEYBOARD,
                )
            val playbackController =
                mockk<RecordingPlaybackController>(relaxed = true) {
                    every { state } returns MutableStateFlow(RecordingPlaybackState())
                }
            every { repository.getRecordingFlow(recordingId) } returns flowOf(RepositoryFlowState(rawRecording))
            stubSupportingFlows(recordingId)

            val viewModel = createViewModel(recordingId = recordingId.toString(), playbackController = playbackController)
            runCurrent()
            advanceTimeBy(ChirpMotion.RECORD_HANDOFF_MS)
            runCurrent()

            viewModel.togglePlayPause()
            viewModel.seekTo(500L)
            viewModel.skipForward()
            viewModel.skipBackward()
            viewModel.shareAudio(context)
            viewModel.shareBoth(context)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isAudioReady)
            assertTrue(isPlaybackAndShareReadyAudioPath("/tmp/cloud-dictation.wav"))
            verify(exactly = 0) { playbackController.onStudioOpened(any(), any(), any()) }
            verify(exactly = 0) { playbackController.play(any(), any(), any()) }
            verify(exactly = 0) { playbackController.prepare(any(), any(), any()) }
            verify(exactly = 0) { playbackController.skipForward() }
            verify(exactly = 0) { playbackController.skipBackward() }
            verify(exactly = 0) { context.startActivity(any()) }
        }

    @Test
    fun `playback tick carries active segment index without clobbering it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val playbackState =
                MutableStateFlow(
                    RecordingPlaybackState(
                        recordingId = recordingId,
                        title = "Meeting",
                        audioPath = "/tmp/test.m4a",
                        positionMs = 0L,
                        isPlaying = true,
                    ),
                )
            val playbackController =
                mockk<RecordingPlaybackController>(relaxed = true) {
                    every { state } returns playbackState
                }
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            every { repository.getTranscriptFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleTranscript(recordingId, rawText = "hello world again")))
            every { repository.getTranscriptTimingsFlow(recordingId) } returns
                flowOf(
                    RepositoryFlowState(
                        listOf(
                            TranscriptTiming(recordingId, 0, "hello", 0L, 100L),
                            TranscriptTiming(recordingId, 1, "world", 100L, 250L),
                            TranscriptTiming(recordingId, 2, "again", 250L, 400L),
                        ),
                    ),
                )
            every { repository.getStructuredOutcomeSnapshotFlow(recordingId) } returns flowOf(RepositoryFlowState(null))

            val viewModel = createViewModel(recordingId = recordingId.toString(), playbackController = playbackController)
            advanceUntilIdle()

            playbackState.value = playbackState.value.copy(positionMs = 150L)
            advanceUntilIdle()

            assertEquals(150L, viewModel.playbackTick.value.currentPositionMs)
            assertEquals(1, viewModel.playbackTick.value.activeTranscriptSegmentIndex)
            assertTrue(viewModel.uiState.value.isPlaying)
        }

    @Test
    fun `entering transcript edit mode clears the karaoke highlight in the playback tick`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val playbackState =
                MutableStateFlow(
                    RecordingPlaybackState(
                        recordingId = recordingId,
                        title = "Meeting",
                        audioPath = "/tmp/test.m4a",
                        positionMs = 150L,
                        isPlaying = false,
                    ),
                )
            val playbackController =
                mockk<RecordingPlaybackController>(relaxed = true) {
                    every { state } returns playbackState
                }
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            every { repository.getTranscriptFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleTranscript(recordingId, rawText = "hello world again")))
            every { repository.getTranscriptTimingsFlow(recordingId) } returns
                flowOf(
                    RepositoryFlowState(
                        listOf(
                            TranscriptTiming(recordingId, 0, "hello", 0L, 100L),
                            TranscriptTiming(recordingId, 1, "world", 100L, 250L),
                            TranscriptTiming(recordingId, 2, "again", 250L, 400L),
                        ),
                    ),
                )
            every { repository.getStructuredOutcomeSnapshotFlow(recordingId) } returns flowOf(RepositoryFlowState(null))

            val viewModel = createViewModel(recordingId = recordingId.toString(), playbackController = playbackController)
            advanceUntilIdle()
            assertEquals(1, viewModel.playbackTick.value.activeTranscriptSegmentIndex)

            viewModel.startEditingTranscript()
            advanceUntilIdle()

            assertEquals(-1, viewModel.playbackTick.value.activeTranscriptSegmentIndex)
        }

    @Test
    fun `saved transcript correction offers word replacement promotion`() =
        runTest {
            // PLH-7: a single-word correction triggers the promotion snackbar offer.
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            every { repository.getTranscriptFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleTranscript(recordingId, rawText = "hello wrold there")))
            every { repository.getTranscriptTimingsFlow(recordingId) } returns flowOf(RepositoryFlowState(emptyList()))
            every { repository.getStructuredOutcomeSnapshotFlow(recordingId) } returns flowOf(RepositoryFlowState(null))

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            viewModel.startEditingTranscript()
            viewModel.updateTranscriptDraft("hello world there")
            viewModel.saveTranscriptCorrection()
            advanceUntilIdle()

            coVerify {
                repository.saveManualCorrection(
                    recordingId = recordingId,
                    correctedText = "hello world there",
                    sourceText = "hello wrold there",
                )
            }
            assertEquals(
                TranscriptCorrectionPromotionPrompt(original = "wrold", replacement = "world"),
                viewModel.promotionPrompt.value,
            )

            viewModel.clearPromotionPrompt()
            assertEquals(null, viewModel.promotionPrompt.value)
        }

    @Test
    fun `multi sentence rewrite does not offer promotion`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            every { repository.getTranscriptFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleTranscript(recordingId, rawText = "alpha beta gamma")))
            every { repository.getTranscriptTimingsFlow(recordingId) } returns flowOf(RepositoryFlowState(emptyList()))
            every { repository.getStructuredOutcomeSnapshotFlow(recordingId) } returns flowOf(RepositoryFlowState(null))

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            viewModel.startEditingTranscript()
            viewModel.updateTranscriptDraft("totally different text entirely now")
            viewModel.saveTranscriptCorrection()
            advanceUntilIdle()

            assertEquals(null, viewModel.promotionPrompt.value)
        }

    @Test
    fun `mid-edit transcript draft is restored after process death`() =
        runTest {
            // LIF-05: saved-state mirrored edit state reopens edit mode with the draft intact.
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            stubSupportingFlows(recordingId)
            every { repository.getTranscriptFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleTranscript(recordingId, rawText = "original text")))

            val restoredHandle =
                SavedStateHandle(
                    mapOf(
                        "recordingId" to recordingId.toString(),
                        "studio.isEditingTranscript" to true,
                        "studio.transcriptDraft" to "my half-typed correction",
                        "studio.chatDraft" to "pending chat question",
                    ),
                )
            val viewModel = createViewModel(recordingId = recordingId.toString(), savedStateHandle = restoredHandle)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isEditingTranscript)
            assertEquals("my half-typed correction", viewModel.uiState.value.transcriptDraft)
            assertEquals("pending chat question", viewModel.uiState.value.chatDraft)
        }

    @Test
    fun `transcript draft mirrors into saved state while editing`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            stubSupportingFlows(recordingId)
            every { repository.getTranscriptFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleTranscript(recordingId, rawText = "original text")))

            val handle = SavedStateHandle(mapOf("recordingId" to recordingId.toString()))
            val viewModel = createViewModel(recordingId = recordingId.toString(), savedStateHandle = handle)
            advanceUntilIdle()

            viewModel.startEditingTranscript()
            viewModel.updateTranscriptDraft("typed so far")

            assertEquals(true, handle.get<Boolean>("studio.isEditingTranscript"))
            assertEquals("typed so far", handle.get<String>("studio.transcriptDraft"))

            viewModel.cancelEditingTranscript()
            assertEquals(false, handle.get<Boolean>("studio.isEditingTranscript"))
            assertEquals(null, handle.get<String>("studio.transcriptDraft"))
        }

    @Test
    fun `background transcript write keeps a mid-edit draft and edit mode`() =
        runTest {
            // A pipeline write (enhancement finishing) landing while the user types must not
            // discard the draft or force-exit edit mode.
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            stubSupportingFlows(recordingId)
            val transcriptFlow =
                MutableStateFlow<RepositoryFlowState<Transcript?>>(
                    RepositoryFlowState(sampleTranscript(recordingId, rawText = "original text")),
                )
            every { repository.getTranscriptFlow(recordingId) } returns transcriptFlow

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            viewModel.startEditingTranscript()
            viewModel.updateTranscriptDraft("half-typed correction")
            advanceUntilIdle()

            transcriptFlow.value =
                RepositoryFlowState(
                    sampleTranscript(recordingId, rawText = "original text").copy(processedText = "polished text"),
                )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isEditingTranscript)
            assertEquals("half-typed correction", viewModel.uiState.value.transcriptDraft)
        }

    @Test
    fun `recording row duration seeds the ui state before playback loads media`() =
        runTest {
            // Regression (sweep-03/04): the header pill and player total read uiState.durationMs,
            // which was only ever fed from Media3 playback — a finalized recording showed 0:00
            // until its first playback. The persisted row duration must seed the state.
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId).copy(durationMs = 38_000L)))
            stubSupportingFlows(recordingId)

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            assertEquals(38_000L, viewModel.uiState.value.durationMs)
        }

    @Test
    fun `playback-reported duration stays authoritative over the persisted row duration`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val recordingFlow =
                MutableStateFlow<RepositoryFlowState<Recording?>>(
                    RepositoryFlowState(sampleRecording(recordingId).copy(durationMs = 38_000L)),
                )
            every { repository.getRecordingFlow(recordingId) } returns recordingFlow
            stubSupportingFlows(recordingId)
            val playbackFlow = MutableStateFlow(RecordingPlaybackState())
            val playbackController =
                mockk<RecordingPlaybackController>(relaxed = true) {
                    every { state } returns playbackFlow
                }

            val viewModel = createViewModel(recordingId = recordingId.toString(), playbackController = playbackController)
            advanceUntilIdle()
            assertEquals(38_000L, viewModel.uiState.value.durationMs)

            // Media3 reports its own measurement once the file loads…
            playbackFlow.value = RecordingPlaybackState(recordingId = recordingId, durationMs = 38_057L)
            advanceUntilIdle()
            assertEquals(38_057L, viewModel.uiState.value.durationMs)

            // …and a later row re-emission (e.g. a title edit) must not clobber it.
            recordingFlow.value = RepositoryFlowState(sampleRecording(recordingId).copy(durationMs = 38_000L, title = "Renamed"))
            advanceUntilIdle()
            assertEquals(38_057L, viewModel.uiState.value.durationMs)
        }

    @Test
    fun `cancelTranscription cancels processing and reports it`() =
        runTest {
            // PIPE-07: studio cancel affordance routes through the recovery port.
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(
                    RepositoryFlowState(
                        sampleRecording(recordingId).copy(status = RecordingStatus.PENDING_TRANSCRIPTION),
                    ),
                )
            stubSupportingFlows(recordingId)
            coEvery { transcriptionRecovery.cancelProcessing(recordingId) } returns Unit

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            viewModel.cancelTranscription()
            advanceUntilIdle()

            coVerify { transcriptionRecovery.cancelProcessing(recordingId) }
            assertEquals("Transcription cancelled", viewModel.message.value)
        }

    private fun TestScope.createViewModel(
        recordingId: String,
        playbackController: RecordingPlaybackController =
            mockk(relaxed = true) {
                every { state } returns MutableStateFlow(RecordingPlaybackState())
            },
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("recordingId" to recordingId)),
        llmClient: LlmClient = mockk(relaxed = true),
        hasApiKey: Boolean = false,
    ): ProcessingStudioViewModel {
        val llmPreferences =
            mockk<LlmPreferences>(relaxed = true) {
                every { llmEnabled } returns MutableStateFlow(false)
                every { hasApiKey() } returns hasApiKey
            }
        return ProcessingStudioViewModel(
            context = context,
            savedStateHandle = savedStateHandle,
            repository = repository,
            llmClient = llmClient,
            llmPreferences = llmPreferences,
            wordReplacementRepository = mockk(relaxed = true),
            transcriptionRecovery = transcriptionRecovery,
            playbackController = playbackController,
        ).apply {
            // Keep the off-main transcript build under the test scheduler so advanceUntilIdle waits for it.
            transcriptBuildDispatcher = StandardTestDispatcher(testScheduler)
        }
    }

    // --- TST-003: studio delete journey (cascade call + playback stop + navigation callback) ---

    @Test
    fun `deleteRecording stops playback of the deleted recording deletes the row and notifies`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val audioFile = File.createTempFile("studio-delete", ".m4a").apply { writeText("audio") }
            val recording = sampleRecording(recordingId).copy(audioPath = audioFile.absolutePath)
            every { repository.getRecordingFlow(recordingId) } returns flowOf(RepositoryFlowState(recording))
            stubSupportingFlows(recordingId)
            coEvery { repository.getRecording(recordingId) } returns recording
            val playbackController =
                mockk<RecordingPlaybackController>(relaxed = true) {
                    every { state } returns
                        MutableStateFlow(
                            RecordingPlaybackState(
                                recordingId = recordingId,
                                title = "Meeting",
                                audioPath = audioFile.absolutePath,
                                isPlaying = true,
                            ),
                        )
                }
            val viewModel = createViewModel(recordingId = recordingId.toString(), playbackController = playbackController)
            advanceUntilIdle()

            val deleted = CompletableDeferred<Unit>()
            viewModel.deleteRecording { deleted.complete(Unit) }
            advanceUntilIdle()
            // The file removal hops to Dispatchers.IO; runTest keeps draining the shared
            // scheduler while awaiting the onDeleted signal (bounded by runTest's timeout).
            deleted.await()

            verify { playbackController.stop() }
            coVerify { transcriptionRecovery.cancelProcessing(recordingId) }
            coVerify { repository.delete(recording) }
            assertFalse(audioFile.exists())
            assertNull(viewModel.message.value)
        }

    @Test
    fun `deleteRecording still navigates back when the row is already gone`() =
        runTest {
            // The user confirmed a delete; a row removed elsewhere must not turn the button
            // into a silent no-op that strands them on a dead screen.
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            stubSupportingFlows(recordingId)
            coEvery { repository.getRecording(recordingId) } returns null

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            var onDeletedInvoked = false
            viewModel.deleteRecording { onDeletedInvoked = true }
            advanceUntilIdle()

            assertTrue(onDeletedInvoked)
            coVerify(exactly = 0) { repository.delete(any()) }
        }

    @Test
    fun `deleteRecording leaves playback of a different recording running`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val otherRecordingId = UUID.randomUUID()
            val audioFile = File.createTempFile("studio-delete", ".m4a").apply { writeText("audio") }
            val recording = sampleRecording(recordingId).copy(audioPath = audioFile.absolutePath)
            every { repository.getRecordingFlow(recordingId) } returns flowOf(RepositoryFlowState(recording))
            stubSupportingFlows(recordingId)
            coEvery { repository.getRecording(recordingId) } returns recording
            val playbackController =
                mockk<RecordingPlaybackController>(relaxed = true) {
                    every { state } returns
                        MutableStateFlow(
                            RecordingPlaybackState(
                                recordingId = otherRecordingId,
                                title = "Other",
                                audioPath = "/tmp/other.m4a",
                                isPlaying = true,
                            ),
                        )
                }
            val viewModel = createViewModel(recordingId = recordingId.toString(), playbackController = playbackController)
            advanceUntilIdle()

            val deleted = CompletableDeferred<Unit>()
            viewModel.deleteRecording { deleted.complete(Unit) }
            advanceUntilIdle()
            deleted.await()

            verify(exactly = 0) { playbackController.stop() }
            coVerify { repository.delete(recording) }
        }

    @Test
    fun `deleteRecording db failure keeps the audio file and skips the navigation callback`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            val audioFile = File.createTempFile("studio-delete", ".m4a").apply { writeText("audio") }
            val recording = sampleRecording(recordingId).copy(audioPath = audioFile.absolutePath)
            every { repository.getRecordingFlow(recordingId) } returns flowOf(RepositoryFlowState(recording))
            stubSupportingFlows(recordingId)
            coEvery { repository.getRecording(recordingId) } returns recording
            coEvery { repository.delete(recording) } throws RuntimeException("db down")

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            var onDeletedInvoked = false
            viewModel.deleteRecording { onDeletedInvoked = true }
            advanceUntilIdle()

            // Data-loss guard: if the row could not be deleted, the audio must survive and
            // the screen must not navigate away as if the delete had succeeded.
            assertFalse(onDeletedInvoked)
            assertTrue(audioFile.exists())
            assertEquals("Couldn't delete the recording", viewModel.message.value)
            audioFile.delete()
        }

    // --- PLH-6: studio selection-mode action lifecycle at the ViewModel level ---

    @Test
    fun `selection action success lands the result and clears the in-flight marker`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            stubSupportingFlows(recordingId)
            every { repository.getTranscriptFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleTranscript(recordingId, rawText = "alpha beta gamma")))
            val llmClient =
                mockk<LlmClient>(relaxed = true) {
                    coEvery { generateTranscriptPassageResponse(TranscriptPassageAction.SUMMARIZE, "alpha beta") } returns
                        Result.success(" Brief summary ")
                }

            val viewModel = createViewModel(recordingId = recordingId.toString(), llmClient = llmClient, hasApiKey = true)
            advanceUntilIdle()

            viewModel.enterTranscriptSelectionMode()
            viewModel.onTranscriptSelectionChanged("alpha beta")
            viewModel.runTranscriptSelectionAction(TranscriptPassageAction.SUMMARIZE)
            assertEquals(TranscriptPassageAction.SUMMARIZE, viewModel.uiState.value.transcriptSelectionActionInFlight)
            advanceUntilIdle()

            assertEquals(
                TranscriptSelectionResult(action = TranscriptPassageAction.SUMMARIZE, text = "Brief summary"),
                viewModel.uiState.value.transcriptSelectionResult,
            )
            assertNull(viewModel.uiState.value.transcriptSelectionActionInFlight)
        }

    @Test
    fun `selection action failure clears the in-flight marker and surfaces a friendly message`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            every { context.getString(R.string.rec_ai_failure_generic) } returns
                "Couldn't reach the AI service"
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            stubSupportingFlows(recordingId)
            every { repository.getTranscriptFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleTranscript(recordingId, rawText = "alpha beta gamma")))
            val llmClient =
                mockk<LlmClient>(relaxed = true) {
                    coEvery { generateTranscriptPassageResponse(any(), any()) } returns
                        Result.failure(IllegalStateException("HTTP 500"))
                }

            val viewModel = createViewModel(recordingId = recordingId.toString(), llmClient = llmClient, hasApiKey = true)
            advanceUntilIdle()

            viewModel.enterTranscriptSelectionMode()
            viewModel.onTranscriptSelectionChanged("alpha beta")
            viewModel.runTranscriptSelectionAction(TranscriptPassageAction.EXPLAIN)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.transcriptSelectionResult)
            assertNull(viewModel.uiState.value.transcriptSelectionActionInFlight)
            assertEquals("Couldn't reach the AI service", viewModel.message.value)
        }

    @Test
    fun `selection action result is dropped when the selection changed mid-flight`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            stubSupportingFlows(recordingId)
            every { repository.getTranscriptFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleTranscript(recordingId, rawText = "alpha beta gamma")))
            val response = CompletableDeferred<Result<String>>()
            val llmClient =
                mockk<LlmClient>(relaxed = true) {
                    coEvery { generateTranscriptPassageResponse(any(), any()) } coAnswers { response.await() }
                }

            val viewModel = createViewModel(recordingId = recordingId.toString(), llmClient = llmClient, hasApiKey = true)
            advanceUntilIdle()

            viewModel.enterTranscriptSelectionMode()
            viewModel.onTranscriptSelectionChanged("alpha beta")
            viewModel.runTranscriptSelectionAction(TranscriptPassageAction.SUMMARIZE)
            runCurrent()

            // The user re-selects while the request is in flight; the stale response must
            // not land on the new selection.
            viewModel.onTranscriptSelectionChanged("gamma")
            response.complete(Result.success("Stale summary"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.transcriptSelectionResult)
        }

    // --- NOTES: studio note editing (load, save, clear, failure, process-death restore) ---

    @Test
    fun `recording note flows into studio state`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId).copy(notes = "Captured live on the roof")))
            stubSupportingFlows(recordingId)

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            assertEquals("Captured live on the roof", viewModel.uiState.value.notes)
            assertFalse(viewModel.uiState.value.isEditingNotes)
        }

    @Test
    fun `saveNotes persists the trimmed note and leaves edit mode`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            stubSupportingFlows(recordingId)
            coEvery { repository.updateNotes(recordingId, "Standup riff") } returns true

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            viewModel.startEditingNotes()
            assertTrue(viewModel.uiState.value.isEditingNotes)
            viewModel.updateEditedNotes("  Standup riff \n")
            viewModel.saveNotes()
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.updateNotes(recordingId, "Standup riff") }
            assertEquals("Standup riff", viewModel.uiState.value.notes)
            assertFalse(viewModel.uiState.value.isEditingNotes)
        }

    @Test
    fun `saveNotes with cleared text removes the note so the section hides again`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId).copy(notes = "Old note")))
            stubSupportingFlows(recordingId)
            coEvery { repository.updateNotes(recordingId, "") } returns true

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            viewModel.startEditingNotes()
            viewModel.updateEditedNotes("   ")
            viewModel.saveNotes()
            advanceUntilIdle()

            // The repository normalizes blank to NULL; state mirrors the cleared note.
            coVerify(exactly = 1) { repository.updateNotes(recordingId, "") }
            assertEquals("", viewModel.uiState.value.notes)
            assertFalse(viewModel.uiState.value.isEditingNotes)
        }

    @Test
    fun `saveNotes failure keeps edit mode and surfaces a friendly message`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            every { context.getString(R.string.rec_msg_note_save_failed) } returns
                "Couldn't save the note. Try again."
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId)))
            stubSupportingFlows(recordingId)
            coEvery { repository.updateNotes(recordingId, any()) } throws RuntimeException("disk full")

            val viewModel = createViewModel(recordingId = recordingId.toString())
            advanceUntilIdle()

            viewModel.startEditingNotes()
            viewModel.updateEditedNotes("Doomed edit")
            viewModel.saveNotes()
            advanceUntilIdle()

            assertEquals("Couldn't save the note. Try again.", viewModel.message.value)
            assertTrue(viewModel.uiState.value.isEditingNotes)
            assertEquals("Doomed edit", viewModel.uiState.value.editedNotes)
        }

    @Test
    fun `in-progress note edit is restored after process death`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val recordingId = UUID.randomUUID()
            every { repository.getRecordingFlow(recordingId) } returns
                flowOf(RepositoryFlowState(sampleRecording(recordingId).copy(notes = "Persisted note")))
            stubSupportingFlows(recordingId)
            val savedStateHandle = SavedStateHandle(mapOf("recordingId" to recordingId.toString()))

            val firstViewModel =
                createViewModel(recordingId = recordingId.toString(), savedStateHandle = savedStateHandle)
            advanceUntilIdle()
            firstViewModel.startEditingNotes()
            firstViewModel.updateEditedNotes("Persisted note, plus a mid-edit thought")

            // A restored ViewModel (same saved state) reopens the editor with the draft intact.
            val restoredViewModel =
                createViewModel(recordingId = recordingId.toString(), savedStateHandle = savedStateHandle)
            advanceUntilIdle()

            assertTrue(restoredViewModel.uiState.value.isEditingNotes)
            assertEquals(
                "Persisted note, plus a mid-edit thought",
                restoredViewModel.uiState.value.editedNotes,
            )
        }

    private fun stubEmptyRecordingFlows(recordingId: UUID) {
        every { repository.getRecordingFlow(recordingId) } returns flowOf(RepositoryFlowState(null))
        stubSupportingFlows(recordingId)
    }

    private fun stubSupportingFlows(recordingId: UUID) {
        every { repository.getTranscriptFlow(recordingId) } returns flowOf(RepositoryFlowState(null))
        every { repository.getTranscriptTimingsFlow(recordingId) } returns flowOf(RepositoryFlowState(emptyList()))
        every { repository.getStructuredOutcomeSnapshotFlow(recordingId) } returns flowOf(RepositoryFlowState(null))
    }

    private fun sampleRecording(id: UUID): Recording =
        Recording(
            id = id,
            title = "Meeting",
            audioPath = "/tmp/test.m4a",
            status = RecordingStatus.COMPLETED,
            source = RecordingSource.APP,
            createdAt = Date(),
            durationMs = 1_000L,
        )

    private fun sampleTranscript(
        recordingId: UUID,
        rawText: String,
    ): Transcript =
        Transcript(
            recordingId = recordingId,
            rawText = rawText,
        )
}
