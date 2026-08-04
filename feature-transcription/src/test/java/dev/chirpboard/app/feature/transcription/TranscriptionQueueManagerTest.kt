package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS
import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@kotlinx.coroutines.ExperimentalCoroutinesApi
class TranscriptionQueueManagerTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var recordingRepository: RecordingRepository
    private lateinit var constraintChecker: WorkConstraintChecker
    private lateinit var manager: TranscriptionQueueManager
    private lateinit var transcriptionRoutingStore: TranscriptionRoutingStore
    private lateinit var workScheduler: FakeTranscriptionWorkScheduler

    @Before
    fun setup() {
        recordingRepository = mockk(relaxed = true)
        constraintChecker = mockk(relaxed = true)
        transcriptionRoutingStore = mockk(relaxed = true)
        workScheduler = FakeTranscriptionWorkScheduler()

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "test-corr-id"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        coEvery { constraintChecker.checkConstraints() } returns WorkConstraintChecker.ConstraintStatus.Ready
        coEvery { constraintChecker.getConstraintMessage(any()) } returns null
        coEvery { recordingRepository.claimTranscriptionExecution(any(), any(), any(), any()) } returns true
        coEvery { recordingRepository.isAutoTranscribeEnabled(any()) } returns true

        val readinessGate = mockk<SpeechModelReadinessGate>(relaxed = true)
        every { readinessGate.state } returns kotlinx.coroutines.flow.MutableStateFlow(ModelReadinessState.Ready(0L, ModelReadinessVerificationSource.PROCESS_CACHE))
        manager =
            TranscriptionQueueManager(
                recordingRepository = recordingRepository,
                constraintChecker = constraintChecker,
                transcriberProvider = mockk(relaxed = true),
                readinessGate = readinessGate,
                workScheduler = workScheduler,
                transcriptionRoutingStore = transcriptionRoutingStore,
            )
    }

    @After
    fun tearDown() {
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `enqueue sets status to pending and schedules work`() = runTest {
        val id = UUID.randomUUID()
        
        manager.enqueue(id)

        coVerify {
            recordingRepository.claimTranscriptionExecution(id, any(), RecordingStatus.PENDING_TRANSCRIPTION, null)
        }
        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `cloud enqueue requires a network connection`() = runTest {
        val id = UUID.randomUUID()
        coEvery { recordingRepository.getRecording(id) } returns
            Recording(
                id = id,
                title = "Cloud dictation",
                audioPath = "/tmp/cloud.wav",
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                source = RecordingSource.KEYBOARD,
                transcriptionEngineId = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id,
            )

        manager.enqueue(id)

        assertEquals(true, workScheduler.transcriptions.single().requiresNetwork)
    }

    @Test
    fun `cloud enqueue over the duration limit can start offline`() = runTest {
        val id = UUID.randomUUID()
        coEvery { recordingRepository.getRecording(id) } returns
            Recording(
                id = id,
                title = "Long cloud dictation",
                audioPath = "/tmp/long-cloud.wav",
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                source = RecordingSource.KEYBOARD,
                durationMs = GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS + 1,
                transcriptionEngineId = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id,
            )

        manager.enqueue(id)

        assertEquals(false, workScheduler.transcriptions.single().requiresNetwork)
    }

    @Test
    fun `cloud enqueue over the file size limit can start offline`() = runTest {
        val id = UUID.randomUUID()
        val oversizedAudio = File.createTempFile("chirp-oversized-cloud-", ".wav")
        try {
            RandomAccessFile(oversizedAudio, "rw").use { file ->
                file.setLength(GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES + 1)
            }
            coEvery { recordingRepository.getRecording(id) } returns
                Recording(
                    id = id,
                    title = "Oversized cloud dictation",
                    audioPath = oversizedAudio.absolutePath,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    source = RecordingSource.KEYBOARD,
                    transcriptionEngineId = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id,
                )

            manager.enqueue(id)

            assertEquals(false, workScheduler.transcriptions.single().requiresNetwork)
        } finally {
            oversizedAudio.delete()
        }
    }

    @Test
    fun `enqueue skips scheduling when claim is rejected`() = runTest {
        val id = UUID.randomUUID()
        coEvery { recordingRepository.claimTranscriptionExecution(id, any(), any(), any()) } returns false

        manager.enqueue(id)

        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `retry skips scheduling when claim is rejected`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.FAILED
        every { recording.transcriptionEngineId } returns TranscriptionEngine.LOCAL_PARAKEET.id
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.hasUnresolvedEnhancementSnapshot(id) } returns false
        coEvery { recordingRepository.claimTranscriptionExecution(id, any(), any(), any()) } returns false

        val result = manager.retry(id)

        assertEquals(ManualRecoveryResult.NOT_RECOVERABLE_STATE, result)
        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `retry reports not recoverable when recording is no longer failed`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.COMPLETED
        coEvery { recordingRepository.getRecording(id) } returns recording

        val result = manager.retry(id)

        assertEquals(ManualRecoveryResult.NOT_RECOVERABLE_STATE, result)
        coVerify(exactly = 0) {
            recordingRepository.claimTranscriptionExecution(any(), any(), any(), any())
        }
        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
        assertEquals(emptyList<String>(), workScheduler.enhancements.map { it.workName })
    }

    @Test
    fun `retry resets status and re-enqueues if failed`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.FAILED
        every { recording.transcriptionEngineId } returns TranscriptionEngine.LOCAL_PARAKEET.id
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.hasUnresolvedEnhancementSnapshot(id) } returns false

        val result = manager.retry(id)

        assertEquals(ManualRecoveryResult.ENQUEUED, result)
        coVerify {
            recordingRepository.claimTranscriptionExecution(id, any(), RecordingStatus.PENDING_TRANSCRIPTION, null)
        }
        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `retry enqueues enhancement when failed recording has unresolved enhancement snapshot`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.FAILED
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.hasUnresolvedEnhancementSnapshot(id) } returns true
        coEvery { recordingRepository.claimEnhancementExecution(id, any(), any(), any()) } returns true

        val result = manager.retry(id)

        assertEquals(ManualRecoveryResult.ENQUEUED, result)
        coVerify(exactly = 1) {
            recordingRepository.claimEnhancementExecution(id, any(), RecordingStatus.PENDING_ENHANCEMENT, null)
        }
        coVerify(exactly = 0) {
            recordingRepository.claimTranscriptionExecution(id, any(), any(), any())
        }
        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
        assertEquals(listOf(RecordingEnhancementWorkRequest.workName(id)), workScheduler.enhancements.map { it.workName })
    }

    @Test
    fun `retranscribe claims completed recording and schedules work`() = runTest {
        val id = UUID.randomUUID()
        coEvery { recordingRepository.claimRetranscriptionExecution(id, any()) } returns true

        val result = manager.retranscribe(id)

        assertEquals(ManualRecoveryResult.ENQUEUED, result)
        coVerify { recordingRepository.claimRetranscriptionExecution(id, any()) }
        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `retranscribe reports not recoverable when claim is rejected`() = runTest {
        val id = UUID.randomUUID()
        coEvery { recordingRepository.claimRetranscriptionExecution(id, any()) } returns false

        val result = manager.retranscribe(id)

        assertEquals(ManualRecoveryResult.NOT_RECOVERABLE_STATE, result)
        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `retranscribe is blocked while queue work is still active`() = runTest {
        val id = UUID.randomUUID()
        workScheduler.recordingTagInfos[id] = listOf(ScheduledWorkInfo(ScheduledWorkState.RUNNING))

        val result = manager.retranscribe(id)

        assertEquals(ManualRecoveryResult.BLOCKED_ACTIVE_WORK, result)
        coVerify(exactly = 0) { recordingRepository.claimRetranscriptionExecution(any(), any()) }
        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `startup retries recordings failed by foreground service policy`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.id } returns id
        every { recording.status } returns RecordingStatus.FAILED
        every { recording.errorMessage } returns
            "startForegroundService() not allowed due to mAllowStartForeground false: service dev.chirpboard.app/androidx.work.impl.foreground.SystemForegroundService"
        every { recording.transcriptionEngineId } returns TranscriptionEngine.LOCAL_PARAKEET.id

        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED) } returns flowOf(RepositoryFlowState(listOf(recording)))
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.hasUnresolvedEnhancementSnapshot(id) } returns false

        manager.processPendingOnStartup()

        coVerify {
            recordingRepository.claimTranscriptionExecution(id, any(), RecordingStatus.PENDING_TRANSCRIPTION, null)
        }
        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `cancelProcessing cancels work and parks a transcribing row in the manual state`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.TRANSCRIBING
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.markAwaitingManualTranscription(id) } returns true

        manager.cancelProcessing(id)

        assertEquals(listOf(id), workScheduler.cancelledTranscriptions)
        assertEquals(listOf(id), workScheduler.cancelledEnhancements)
        coVerify(exactly = 1) { recordingRepository.markAwaitingManualTranscription(id) }
        coVerify(exactly = 0) { recordingRepository.updateStatusWithError(any(), any(), any()) }
    }

    @Test
    fun `cancelProcessing resolves an enhancing row to a neutral terminal state`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.ENHANCING
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.resolveCancelledEnhancement(id) } returns true

        manager.cancelProcessing(id)

        assertEquals(listOf(id), workScheduler.cancelledTranscriptions)
        assertEquals(listOf(id), workScheduler.cancelledEnhancements)
        coVerify(exactly = 1) { recordingRepository.resolveCancelledEnhancement(id) }
    }

    @Test
    fun `cancelProcessing leaves completed rows untouched`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.COMPLETED
        coEvery { recordingRepository.getRecording(id) } returns recording

        manager.cancelProcessing(id)

        assertEquals(listOf(id), workScheduler.cancelledTranscriptions)
        coVerify(exactly = 0) { recordingRepository.markAwaitingManualTranscription(any()) }
        coVerify(exactly = 0) { recordingRepository.resolveCancelledEnhancement(any()) }
    }

    @Test
    fun `enqueue parks recording in manual state when profile disables auto transcribe`() = runTest {
        val id = UUID.randomUUID()
        coEvery { recordingRepository.isAutoTranscribeEnabled(id) } returns false
        coEvery { recordingRepository.markAwaitingManualTranscription(id) } returns true

        manager.enqueue(id)

        coVerify(exactly = 0) { recordingRepository.claimTranscriptionExecution(any(), any(), any(), any()) }
        coVerify(exactly = 0) { transcriptionRoutingStore.getSelectedEngine() }
        coVerify(exactly = 0) { recordingRepository.stampTranscriptionEngineIfUnset(any(), any()) }
        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `manual retranscription stamps a route after auto transcribe parked the row`() = runTest {
        val id = UUID.randomUUID()
        val parkedRecording =
            Recording(
                id = id,
                title = "Parked dictation",
                audioPath = "/tmp/parked.wav",
                status = RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION,
                source = RecordingSource.KEYBOARD,
            )
        val routedRecording =
            parkedRecording.copy(
                transcriptionEngineId = TranscriptionEngine.LOCAL_PARAKEET.id,
            )
        coEvery { recordingRepository.isAutoTranscribeEnabled(id) } returns false
        coEvery { recordingRepository.markAwaitingManualTranscription(id) } returns true
        coEvery { recordingRepository.getRecording(id) } returns parkedRecording
        coEvery { transcriptionRoutingStore.getSelectedEngine() } returns
            TranscriptionEngine.LOCAL_PARAKEET
        coEvery {
            recordingRepository.stampTranscriptionEngineIfUnset(
                id,
                TranscriptionEngine.LOCAL_PARAKEET.id,
            )
        } returns routedRecording
        coEvery { recordingRepository.claimRetranscriptionExecution(id, any()) } returns true

        manager.enqueue(id)
        coVerify(exactly = 0) { recordingRepository.stampTranscriptionEngineIfUnset(any(), any()) }

        val result = manager.retranscribe(id)

        assertEquals(ManualRecoveryResult.ENQUEUED, result)
        coVerify(exactly = 1) {
            recordingRepository.stampTranscriptionEngineIfUnset(
                id,
                TranscriptionEngine.LOCAL_PARAKEET.id,
            )
        }
        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `enqueue falls through to scheduling when manual-state marking is rejected`() = runTest {
        val id = UUID.randomUUID()
        coEvery { recordingRepository.isAutoTranscribeEnabled(id) } returns false
        coEvery { recordingRepository.markAwaitingManualTranscription(id) } returns false

        manager.enqueue(id)

        coVerify {
            recordingRepository.claimTranscriptionExecution(id, any(), RecordingStatus.PENDING_TRANSCRIPTION, null)
        }
        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `manager and reconciler serialize execution claims through scheduling`() = runTest {
        val directId = UUID.randomUUID()
        val reconciledId = UUID.randomUUID()
        val directRecording =
            Recording(
                id = directId,
                title = "Direct enqueue",
                audioPath = "/tmp/direct.wav",
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                source = RecordingSource.KEYBOARD,
                transcriptionEngineId = TranscriptionEngine.LOCAL_PARAKEET.id,
            )
        val pendingRecording =
            Recording(
                id = reconciledId,
                title = "Reconciled enqueue",
                audioPath = "/tmp/reconciled.wav",
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                source = RecordingSource.KEYBOARD,
                transcriptionEngineId = TranscriptionEngine.LOCAL_PARAKEET.id,
            )
        coEvery { recordingRepository.getRecording(directId) } returns directRecording
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns
            flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns
            flowOf(RepositoryFlowState(listOf(pendingRecording)))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns
            flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns
            flowOf(RepositoryFlowState(emptyList()))
        workScheduler.uniqueWorkInfos[TranscriptionWorkRequest.workName(reconciledId)] =
            listOf(ScheduledWorkInfo(ScheduledWorkState.CANCELLED))

        val firstClaimStarted = CompletableDeferred<Unit>()
        val releaseFirstClaim = CompletableDeferred<Unit>()
        val claimOrder = mutableListOf<UUID>()
        coEvery {
            recordingRepository.claimTranscriptionExecution(any(), any(), any(), any())
        } coAnswers {
            val recordingId = firstArg<UUID>()
            claimOrder += recordingId
            if (recordingId == directId) {
                firstClaimStarted.complete(Unit)
                releaseFirstClaim.await()
            }
            true
        }

        val reconciler =
            TranscriptionQueueReconciler(
                recordingRepository = recordingRepository,
                constraintChecker = constraintChecker,
                workScheduler = workScheduler,
                transcriptionRoutingStore = transcriptionRoutingStore,
                setConstraintWarning = {},
                setActiveCount = {},
            )
        val directEnqueue = launch { manager.enqueue(directId) }
        firstClaimStarted.await()
        val reconciliation =
            launch { reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC) }
        runCurrent()

        assertEquals(listOf(directId), claimOrder)
        assertEquals(emptyList<UUID>(), workScheduler.transcriptions.map { it.recordingId })

        releaseFirstClaim.complete(Unit)
        directEnqueue.join()
        reconciliation.join()

        assertEquals(listOf(directId, reconciledId), claimOrder)
        assertEquals(
            listOf(directId, reconciledId),
            workScheduler.transcriptions.map { it.recordingId },
        )
    }

    @Test
    fun `event-driven reconciliation runs once for an empty queue and does not poll`() = runTest {
        val repo = freshRepository()
        val empty = kotlinx.coroutines.flow.MutableStateFlow(RepositoryFlowState(emptyList<Recording>()))
        every { repo.getRecordingsByStatus(any()) } returns empty
        val eventManager = newEventDrivenManager(repo)

        eventManager.startContinuousReconciliation(backgroundScope, activeIntervalMs = 60_000L)
        runCurrent()
        advanceUntilIdle()

        // One reconciliation pass for the initial empty emission. ENHANCING is queried once
        // per pass (recoverStaleEnhancing) plus once at construction (building the signature
        // flow operand), so the baseline after the first pass is 2.
        coVerify(exactly = 2) { repo.getRecordingsByStatus(RecordingStatus.ENHANCING) }

        // No safety-net timer runs while the queue is empty, so advancing far past the
        // interval triggers no further passes: the count stays at the baseline.
        advanceTimeBy(10 * 60_000L)
        advanceUntilIdle()
        coVerify(exactly = 2) { repo.getRecordingsByStatus(RecordingStatus.ENHANCING) }
    }

    @Test
    fun `event-driven reconciliation reconciles again when work appears`() = runTest {
        val repo = freshRepository()
        val transcribing = kotlinx.coroutines.flow.MutableStateFlow(RepositoryFlowState(emptyList<Recording>()))
        val empty = kotlinx.coroutines.flow.MutableStateFlow(RepositoryFlowState(emptyList<Recording>()))
        every { repo.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns transcribing
        every { repo.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns empty
        every { repo.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns empty
        every { repo.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns empty
        val eventManager = newEventDrivenManager(repo)

        eventManager.startContinuousReconciliation(backgroundScope, activeIntervalMs = 60_000L)
        runCurrent()
        advanceUntilIdle()

        val recording = mockk<Recording>(relaxed = true)
        every { recording.id } returns UUID.randomUUID()
        every { recording.status } returns RecordingStatus.TRANSCRIBING
        every { recording.createdAt } returns java.util.Date(System.currentTimeMillis())
        transcribing.value = RepositoryFlowState(listOf(recording))
        runCurrent()
        advanceUntilIdle()

        // Construction (1) + initial empty pass (1) + the non-empty transition pass (1) = 3
        // ENHANCING queries. The third proves the queue change re-triggered reconciliation.
        coVerify(exactly = 3) { repo.getRecordingsByStatus(RecordingStatus.ENHANCING) }
    }

    private fun freshRepository(): RecordingRepository {
        val repo = mockk<RecordingRepository>(relaxed = true)
        coEvery { repo.claimTranscriptionExecution(any(), any(), any(), any()) } returns true
        return repo
    }

    private fun newEventDrivenManager(repo: RecordingRepository): TranscriptionQueueManager {
        val readinessGate = mockk<SpeechModelReadinessGate>(relaxed = true)
        every { readinessGate.state } returns
            kotlinx.coroutines.flow.MutableStateFlow(
                ModelReadinessState.Ready(0L, ModelReadinessVerificationSource.PROCESS_CACHE),
            )
        return TranscriptionQueueManager(
            recordingRepository = repo,
            constraintChecker = constraintChecker,
            transcriberProvider = mockk(relaxed = true),
            readinessGate = readinessGate,
            workScheduler = workScheduler,
            transcriptionRoutingStore = mockk<TranscriptionRoutingStore>(relaxed = true),
        )
    }

    @Test
    fun `recover pending enhancement enqueues enhancement work`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.PENDING_ENHANCEMENT
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.claimEnhancementExecution(id, any(), any(), any()) } returns true

        manager.recoverPendingEnhancement(id)

        coVerify {
            recordingRepository.claimEnhancementExecution(id, any(), RecordingStatus.PENDING_ENHANCEMENT, any())
        }
        assertEquals(listOf(RecordingEnhancementWorkRequest.workName(id)), workScheduler.enhancements.map { it.workName })
    }
}
