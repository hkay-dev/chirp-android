package dev.chirpboard.app.feature.transcription

import android.util.Log
import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionQueueLifecycle
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadyResult
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.isWaitingForSpeechModel
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the queue of recordings pending transcription.
 *
 * Coordinates between the RecordingRepository for status tracking
 * and WorkManager for background processing.
 */
@Singleton
class TranscriptionQueueManager
    @Inject
    constructor(
        private val recordingRepository: RecordingRepository,
        private val constraintChecker: WorkConstraintChecker,
        private val transcriberProvider: dev.chirpboard.app.core.transcription.TranscriberProvider,
        private val readinessGate: SpeechModelReadinessGate,
        private val workScheduler: TranscriptionWorkScheduler,
        private val transcriptionRoutingStore: TranscriptionRoutingStore,
    ) : TranscriptionRecovery, TranscriptionQueueLifecycle {
        private val reconciliationMutex = Mutex()
        private var reconciliationJob: Job? = null

        @Volatile
        private var reconciliationStarted = false
        private val queueReconciler by lazy {
            TranscriptionQueueReconciler(
                recordingRepository = recordingRepository,
                constraintChecker = constraintChecker,
                workScheduler = workScheduler,
                transcriptionRoutingStore = transcriptionRoutingStore,
                setConstraintWarning = { _constraintWarning.value = it },
                setActiveCount = { _activeCount.value = it },
            )
        }

        companion object {
            private const val TAG = "TranscriptionQueueMgr"

            /**
             * Idle safety-net cadence while non-terminal work exists. Reconciliation is
             * primarily event-driven (it runs whenever the pending/active status flows
             * change), so this only has to catch staleness that no DB transition would
             * otherwise reveal — a row sitting in TRANSCRIBING/ENHANCING past its
             * 10-15 minute stale threshold. A 5-minute cadence matches that granularity;
             * the previous 60s poll fired 10-15x finer than any staleness it could detect.
             * When the queue drains empty this timer stops entirely; the next enqueue
             * re-triggers reconciliation through the status flows.
             */
            private const val ACTIVE_RECONCILIATION_INTERVAL_MS = 5 * 60_000L
        }

        private val _activeCount = MutableStateFlow(0)

        private val _constraintWarning = MutableStateFlow<String?>(null)

        /**
         * Warning message when device constraints may delay transcription.
         * Null when all constraints are satisfied.
         * UI can observe this to show snackbar/banner feedback to users.
         */
        val constraintWarning: StateFlow<String?> = _constraintWarning.asStateFlow()

        /**
         * Flow of recordings pending background processing.
         * Emits updates whenever pending transcription or enhancement work changes.
         */
        val pendingRecordings: Flow<List<Recording>> =
            combine(
                recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION),
                recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT),
            ) { pendingTranscription, pendingEnhancement ->
                mergePendingRecordings(pendingTranscription.value, pendingEnhancement.value)
            }

        /**
         * Number of recordings currently being processed (TRANSCRIBING status).
         */
        val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

        /**
         * Identity-and-count signature of every non-terminal recording. A change here means
         * the queue gained, lost, or transitioned work, which is exactly when reconciliation
         * has something to do; an unchanged signature means the queue is static (and is empty
         * when [QueueWorkSignature.isEmpty]). Reconciliation observes this instead of polling.
         */
        private val nonTerminalWorkSignature: Flow<QueueWorkSignature> =
            combine(
                recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION),
                recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT),
                recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING),
                recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING),
            ) { pendingTranscription, pendingEnhancement, transcribing, enhancing ->
                QueueWorkSignature.of(
                    pendingTranscription.value,
                    pendingEnhancement.value,
                    transcribing.value,
                    enhancing.value,
                )
            }.distinctUntilChanged()

        /**
         * Start event-driven queue reconciliation for the life of [scope].
         * Safe to call multiple times; only the first call starts the observers.
         */
        override fun startContinuousReconciliation(scope: CoroutineScope) {
            startContinuousReconciliation(scope, ACTIVE_RECONCILIATION_INTERVAL_MS)
        }

        /**
         * Reconciliation is driven by the non-terminal status flows rather than a fixed
         * poll: a pass runs whenever the queue changes, and an idle safety-net pass runs
         * every [activeIntervalMs] *only while non-terminal work exists* to catch staleness
         * that produces no DB transition. When the queue is empty no timer runs at all, so a
         * process the IME keeps alive all day no longer wakes Room + WorkManager every minute
         * to confirm an empty queue.
         */
        fun startContinuousReconciliation(
            scope: CoroutineScope,
            activeIntervalMs: Long,
        ) {
            synchronized(this) {
                if (reconciliationStarted) return
                reconciliationStarted = true
            }

            reconciliationJob =
                scope.launch {
                    var idleSafetyNet: Job? = null
                    nonTerminalWorkSignature.collect { signature ->
                        // Every change to the non-terminal set is a reconciliation trigger.
                        runReconciliationPass()

                        if (signature.isEmpty) {
                            // Queue drained: stop the safety-net timer. The next enqueue
                            // re-emits a non-empty signature and reconciliation resumes.
                            idleSafetyNet?.cancel()
                            idleSafetyNet = null
                        } else if (idleSafetyNet?.isActive != true) {
                            idleSafetyNet =
                                scope.launch {
                                    while (isActive) {
                                        delay(activeIntervalMs)
                                        runReconciliationPass()
                                    }
                                }
                        }
                    }
                }
            scope.launch {
                readinessGate.state
                    .map { it is ModelReadinessState.Ready }
                    .distinctUntilChanged()
                    .collect { isReady ->
                        if (isReady) {
                            recoverRecordingsWaitingForModel()
                        }
                    }
            }
        }

        private suspend fun runReconciliationPass() {
            try {
                reconciliationMutex.withLock {
                    queueReconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Queue reconciliation failed", e)
            }
        }

        /**
         * Enqueue a recording for transcription.
         * Sets status to PENDING_TRANSCRIPTION and schedules WorkManager job.
         *
         * Checks device constraints and emits a warning via [constraintWarning] if
         * battery is low or storage is insufficient. The work is still enqueued
         * (WorkManager will wait for constraints), but the user gets feedback.
         *
         * @param recordingId The UUID of the recording to transcribe
         */
        override suspend fun enqueue(
            recordingId: UUID,
            correlationId: String?,
        ): String {
            val corrId = correlationId ?: ReliabilityEventLogger.newCorrelationId("queue")
            val queueLog =
                ReliabilityEventLogger.scoped(
                    stage = ReliabilityStage.QUEUE_ENQUEUE,
                    correlationId = corrId,
                    recordingId = recordingId,
                )
            queueLog.started("enqueue_requested")

            // PLH-4: honor the profile's Auto Transcribe opt-out at the post-finalize
            // enqueue. The recording is parked in the deliberate AWAITING manual state
            // instead of the queue; automatic recovery never loads that status (see
            // RecordingRepository.getPendingRecordings), so only an explicit user
            // retranscribe starts it. When marking fails the row has already moved on
            // (e.g. the user manually claimed it), so fall through to a normal enqueue.
            if (!recordingRepository.isAutoTranscribeEnabled(recordingId) &&
                recordingRepository.markAwaitingManualTranscription(recordingId)
            ) {
                queueLog.skipped("enqueue_skipped_auto_transcribe_disabled")
                return TranscriptionWorkRequest.workName(recordingId)
            }

            val routedRecording = resolveTranscriptionRoute(recordingId)
            val transcriptionEngine =
                TranscriptionEngine.fromId(routedRecording?.transcriptionEngineId)

            // Check constraints and warn user (but still enqueue - WorkManager will wait)
            val status = constraintChecker.checkConstraints()
            _constraintWarning.value = constraintChecker.getConstraintMessage(status)

            val workId =
                try {
                    withSerializedQueueScheduling {
                        val executionToken = UUID.randomUUID().toString()
                        val claimed =
                            recordingRepository.claimTranscriptionExecution(
                                recordingId = recordingId,
                                executionToken = executionToken,
                            )
                        if (!claimed) {
                            return@withSerializedQueueScheduling null
                        }

                        workScheduler.enqueueTranscription(
                            recordingId = recordingId,
                            executionToken = executionToken,
                            correlationId = corrId,
                            requiresNetwork =
                                routedRecording?.requiresNetworkForTranscription() == true,
                        )
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    queueLog.failure("enqueue_exception", e)
                    throw e
                }

            if (workId == null) {
                queueLog.skipped("enqueue_claim_rejected")
                return TranscriptionWorkRequest.workName(recordingId)
            }

            queueLog.success("enqueue_scheduled")
            if (transcriptionEngine == TranscriptionEngine.LOCAL_PARAKEET) {
                readinessGate.verifyIfNeeded(VerificationTrigger.QUEUED_TRANSCRIPTION)
            }
            return workId
        }

        /**
         * Mark a recording as recoverable pending when save succeeded but enqueue failed.
         * Startup recovery can use this marker to prioritize queue reattachment.
         */
        override suspend fun markPendingForQueueRecovery(
            recordingId: UUID,
            reason: String,
            cause: Throwable?,
        ) {
            val causeMessage = cause?.message?.takeIf { it.isNotBlank() }
            val errorMessage =
                if (causeMessage != null) {
                    "$RECOVERABLE_QUEUE_HANDOFF_PREFIX$reason Cause: $causeMessage"
                } else {
                    "$RECOVERABLE_QUEUE_HANDOFF_PREFIX$reason"
                }

            recordingRepository.updateStatusWithError(
                id = recordingId,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                errorMessage = errorMessage,
            )

            ReliabilityEventLogger
                .scoped(
                    stage = ReliabilityStage.QUEUE_ENQUEUE,
                    correlationId = ReliabilityEventLogger.newCorrelationId("queue-recovery"),
                    recordingId = recordingId,
                ).recovered("pending_for_recovery", message = reason)
        }

        /**
         * Retry a failed transcription.
         * Resets status from FAILED to PENDING_TRANSCRIPTION and re-enqueues.
         *
         * Checks device constraints and emits a warning via [constraintWarning] if
         * battery is low or storage is insufficient.
         *
         * @param recordingId The UUID of the recording to retry
         * @return the actual outcome; [ManualRecoveryResult.NOT_RECOVERABLE_STATE] when the
         *   recording is missing, no longer FAILED, or its execution claim was refused, so
         *   callers never report a re-queue that did not happen.
         */
        override suspend fun retry(recordingId: UUID): ManualRecoveryResult {
            val recording = recordingRepository.getRecording(recordingId)

            if (recording?.status != RecordingStatus.FAILED) {
                return ManualRecoveryResult.NOT_RECOVERABLE_STATE
            }

            // Check constraints and warn user
            val status = constraintChecker.checkConstraints()
            _constraintWarning.value = constraintChecker.getConstraintMessage(status)

            val correlationId = ReliabilityEventLogger.newCorrelationId("queue-retry")
            if (recordingRepository.hasUnresolvedEnhancementSnapshot(recordingId)) {
                val scheduled =
                    withSerializedQueueScheduling {
                        val executionToken = UUID.randomUUID().toString()
                        if (!recordingRepository.claimEnhancementExecution(recordingId, executionToken)) {
                            return@withSerializedQueueScheduling false
                        }
                        workScheduler.enqueueEnhancement(
                            recordingId = recordingId,
                            executionToken = executionToken,
                            correlationId = correlationId,
                        )
                        true
                    }
                if (!scheduled) {
                    return ManualRecoveryResult.NOT_RECOVERABLE_STATE
                }
                return ManualRecoveryResult.ENQUEUED
            }

            val routedRecording = resolveTranscriptionRoute(recordingId)
            val transcriptionEngine =
                TranscriptionEngine.fromId(routedRecording?.transcriptionEngineId)
            if (transcriptionEngine == TranscriptionEngine.LOCAL_PARAKEET) {
                warmUpTranscriberIfNeeded(VerificationTrigger.QUEUED_TRANSCRIPTION)
            }

            val scheduled =
                withSerializedQueueScheduling {
                    val executionToken = UUID.randomUUID().toString()
                    val claimed =
                        recordingRepository.claimTranscriptionExecution(
                            recordingId = recordingId,
                            executionToken = executionToken,
                        )
                    if (!claimed) {
                        return@withSerializedQueueScheduling false
                    }
                    workScheduler.enqueueTranscription(
                        recordingId = recordingId,
                        executionToken = executionToken,
                        correlationId = correlationId,
                        requiresNetwork =
                            routedRecording?.requiresNetworkForTranscription() == true,
                    )
                    true
                }
            if (!scheduled) {
                return ManualRecoveryResult.NOT_RECOVERABLE_STATE
            }
            return ManualRecoveryResult.ENQUEUED
        }

        /**
         * Explicit user-requested re-transcription. Claims ownership even from COMPLETED
         * (resetting the row to PENDING_TRANSCRIPTION) before scheduling work, mirroring
         * how [retry] resets FAILED recordings. Returns the actual outcome so the caller
         * never reports success when the claim or scheduling was refused.
         */
        override suspend fun retranscribe(recordingId: UUID): ManualRecoveryResult {
            // No reconciliationMutex: the inspection is a read, and any race with a
            // reconciliation pass is resolved by the status-pinned claim below plus the
            // REPLACE enqueue (both under the scheduling mutex). Taking the lock here
            // would stall this user action behind a full pass, which holds it across
            // one WorkManager query (5s timeout each) per queued recording.
            val ownership = queueReconciler.inspectQueueOwnership(recordingId)
            val blockResult = blockedManualRecoveryResult(ownership)
            if (blockResult != null) {
                return blockResult
            }

            val constraintStatus = constraintChecker.checkConstraints()
            _constraintWarning.value = constraintChecker.getConstraintMessage(constraintStatus)

            val correlationId = ReliabilityEventLogger.newCorrelationId("queue-retranscribe")
            val routedRecording = resolveTranscriptionRoute(recordingId)
            val transcriptionEngine =
                TranscriptionEngine.fromId(routedRecording?.transcriptionEngineId)
            val queueLog =
                ReliabilityEventLogger.scoped(
                    stage = ReliabilityStage.QUEUE_ENQUEUE,
                    correlationId = correlationId,
                    recordingId = recordingId,
                )
            val scheduled =
                withSerializedQueueScheduling {
                    val executionToken = UUID.randomUUID().toString()
                    val claimed =
                        recordingRepository.claimRetranscriptionExecution(
                            recordingId = recordingId,
                            executionToken = executionToken,
                        )
                    if (!claimed) {
                        return@withSerializedQueueScheduling false
                    }

                    workScheduler.enqueueTranscription(
                        recordingId = recordingId,
                        executionToken = executionToken,
                        correlationId = correlationId,
                        requiresNetwork =
                            routedRecording?.requiresNetworkForTranscription() == true,
                    )
                    true
                }
            if (!scheduled) {
                queueLog.skipped("retranscribe_claim_rejected")
                return ManualRecoveryResult.NOT_RECOVERABLE_STATE
            }

            queueLog.success("retranscribe_scheduled")
            if (transcriptionEngine == TranscriptionEngine.LOCAL_PARAKEET) {
                readinessGate.verifyIfNeeded(VerificationTrigger.QUEUED_TRANSCRIPTION)
            }
            return ManualRecoveryResult.ENQUEUED
        }

        override suspend fun recoverPendingTranscription(recordingId: UUID): ManualRecoveryResult {
            val recording =
                recordingRepository.getRecording(recordingId)
                    ?: return ManualRecoveryResult.NOT_RECOVERABLE_STATE

            if (recording.status != RecordingStatus.PENDING_TRANSCRIPTION) {
                return ManualRecoveryResult.NOT_RECOVERABLE_STATE
            }

            return enqueueManualRecovery(
                recordingId = recordingId,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                reason = "Re-established pending transcription ownership",
            )
        }

        override suspend fun recoverPendingEnhancement(recordingId: UUID): ManualRecoveryResult {
            val recording =
                recordingRepository.getRecording(recordingId)
                    ?: return ManualRecoveryResult.NOT_RECOVERABLE_STATE

            if (recording.status != RecordingStatus.PENDING_ENHANCEMENT) {
                return ManualRecoveryResult.NOT_RECOVERABLE_STATE
            }

            return enqueueManualRecovery(
                recordingId = recordingId,
                status = RecordingStatus.PENDING_ENHANCEMENT,
                reason = "Re-established pending enhancement ownership",
            )
        }

        override suspend fun recoverEnhancing(recordingId: UUID): ManualRecoveryResult {
            val recording =
                recordingRepository.getRecording(recordingId)
                    ?: return ManualRecoveryResult.NOT_RECOVERABLE_STATE

            if (recording.status != RecordingStatus.ENHANCING) {
                return ManualRecoveryResult.NOT_RECOVERABLE_STATE
            }

            return enqueueManualRecovery(
                recordingId = recordingId,
                status = RecordingStatus.PENDING_ENHANCEMENT,
                reason = "Queued enhancement-only recovery",
            )
        }

        override suspend fun retranscribeFromEnhancing(recordingId: UUID): ManualRecoveryResult {
            val recording =
                recordingRepository.getRecording(recordingId)
                    ?: return ManualRecoveryResult.NOT_RECOVERABLE_STATE

            if (recording.status != RecordingStatus.ENHANCING) {
                return ManualRecoveryResult.NOT_RECOVERABLE_STATE
            }

            return enqueueManualRecovery(
                recordingId = recordingId,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                reason = "Queued full retranscription from enhancing",
                supersedeEnhancement = true,
            )
        }

        override suspend fun recoverStuckRecordings(): Int {
            val pending = recordingRepository.getPendingRecordings()
            val enhancing =
                recordingRepository
                    .getRecordingsByStatus(RecordingStatus.ENHANCING)
                    .first()
                    .value
            val pendingById = pending.associateBy { it.id }

            return (pending.map { it.id } + enhancing.map { it.id }).count { id ->
                when (pendingById[id]?.status) {
                    RecordingStatus.PENDING_TRANSCRIPTION -> {
                        recoverPendingTranscription(id) == ManualRecoveryResult.ENQUEUED
                    }

                    RecordingStatus.PENDING_ENHANCEMENT -> {
                        recoverPendingEnhancement(id) == ManualRecoveryResult.ENQUEUED
                    }

                    else -> {
                        recoverEnhancing(id) == ManualRecoveryResult.ENQUEUED
                    }
                }
            }
        }

        // Pure read for the diagnostics UI; see retranscribe for why it must not wait
        // behind a reconciliation pass.
        override suspend fun getRecoveryDiagnostics(recordingId: UUID): RecoveryDiagnostics =
            queueReconciler.getRecoveryDiagnostics(recordingId)

        override suspend fun recoverRecordingsWaitingForModel() {
            if (!transcriberProvider.isModelDownloaded()) {
                return
            }
            val failed = recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED).first().value
            // I18N-06: typed classification of the frozen persisted markers (data module owns it).
            val waitingForModel = failed.filter { isWaitingForSpeechModel(it.errorMessage) }
            if (waitingForModel.isEmpty()) {
                return
            }
            if (!warmUpTranscriberIfNeeded(VerificationTrigger.RECOVERY)) {
                Log.w(TAG, "Speech model files are ready but recognizer init is still unavailable")
                return
            }

            waitingForModel.forEach { recording ->
                retry(recording.id)
            }
        }

        private suspend fun recoverRecordingsFailedByForegroundServicePolicy() {
            val failed = recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED).first().value
            failed
                .filter { recording -> recording.errorMessage.isForegroundServicePolicyFailure() }
                .forEach { recording ->
                    Log.w(TAG, "Recovering foreground-service policy failure for ${recording.id}")
                    retry(recording.id)
                }
        }

        private suspend fun warmUpTranscriberIfNeeded(trigger: VerificationTrigger): Boolean {
            if (transcriberProvider.isReady()) {
                return true
            }
            when (readinessGate.ensureReady(trigger)) {
                is ModelReadyResult.Ready -> Unit
                else -> return false
            }
            return transcriberProvider.initialize()
        }

        /**
         * PIPE-07: user-facing cancel for queued/running processing, also called before
         * deleting a recording so an orphaned worker never spins up for a deleted row.
         *
         * Work is cancelled first, then the row resolves to a *neutral* state instead of
         * FAILED-with-error: a not-yet-transcribed recording becomes
         * AWAITING_MANUAL_TRANSCRIPTION (re-startable via retranscribe), a recording whose
         * transcript already committed keeps it and becomes COMPLETED. Both transitions
         * clear the execution token, so a worker that races the cancel sees its commit
         * rejected as stale — the execution-token contract stays intact.
         */
        override suspend fun cancelProcessing(recordingId: UUID) {
            workScheduler.cancelTranscription(recordingId)
            workScheduler.cancelEnhancement(recordingId)

            val recording = recordingRepository.getRecording(recordingId) ?: return
            val resolved =
                when (recording.status) {
                    RecordingStatus.PENDING_TRANSCRIPTION,
                    RecordingStatus.TRANSCRIBING,
                    -> recordingRepository.markAwaitingManualTranscription(recordingId)

                    RecordingStatus.PENDING_ENHANCEMENT,
                    RecordingStatus.ENHANCING,
                    -> recordingRepository.resolveCancelledEnhancement(recordingId)

                    else -> false
                }
            if (resolved) {
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.QUEUE_ENQUEUE,
                        correlationId = ReliabilityEventLogger.newCorrelationId("queue-cancel"),
                        recordingId = recordingId,
                    ).success("processing_cancelled_by_user")
            }
        }

        /**
         * Clear the constraint warning.
         * Call this after the UI has displayed the warning to the user.
         */
        fun clearConstraintWarning() {
            _constraintWarning.value = null
        }

        /**
         * Process all pending recordings on app startup.
         * Call this from Application.onCreate or a startup initializer.
         *
         * First recovers any recordings stuck in TRANSCRIBING status (from app kill),
         * then queries all PENDING_TRANSCRIPTION recordings and ensures each
         * has a WorkManager job scheduled.
         *
         * Also checks device constraints and emits a warning if there are pending
         * recordings but constraints are not met.
         */
        override suspend fun processPendingOnStartup() {
            reconciliationMutex.withLock {
                queueReconciler.reconcileQueueHealth(ReconciliationTrigger.STARTUP)
            }
            val pending = recordingRepository.getPendingRecordings()
            if (pending.anyLocalTranscription()) {
                readinessGate.verifyIfNeeded(VerificationTrigger.QUEUED_TRANSCRIPTION)
            }
            if (transcriberProvider.isModelDownloaded()) {
                recoverRecordingsWaitingForModel()
            }
            recoverRecordingsFailedByForegroundServicePolicy()
        }

        private suspend fun enqueueManualRecovery(
            recordingId: UUID,
            status: RecordingStatus,
            reason: String,
            supersedeEnhancement: Boolean = false,
        ): ManualRecoveryResult {
            // Read-only inspection; see retranscribe for why the reconciliation mutex
            // is deliberately not taken here.
            val ownership = queueReconciler.inspectQueueOwnership(recordingId)
            val blockResult = blockedManualRecoveryResult(ownership)
            if (blockResult != null) {
                return blockResult
            }

            val statusCheck = constraintChecker.checkConstraints()
            _constraintWarning.value = constraintChecker.getConstraintMessage(statusCheck)

            val routedRecording =
                if (status == RecordingStatus.PENDING_TRANSCRIPTION) {
                    resolveTranscriptionRoute(recordingId)
                } else {
                    null
                }
            val manualRecoveryMessage = buildManualRecoveryMessage(reason)
            val scheduled =
                withSerializedQueueScheduling {
                    val executionToken = UUID.randomUUID().toString()
                    when (status) {
                        RecordingStatus.PENDING_ENHANCEMENT -> {
                            if (!recordingRepository.claimEnhancementExecution(recordingId, executionToken, status, manualRecoveryMessage)) {
                                return@withSerializedQueueScheduling false
                            }
                        }

                        else -> {
                            if (supersedeEnhancement) {
                                recordingRepository.deleteEnhancementSnapshot(recordingId)
                            }
                            val claimed =
                                recordingRepository.claimTranscriptionExecution(
                                    recordingId = recordingId,
                                    executionToken = executionToken,
                                    status = status,
                                    errorMessage = manualRecoveryMessage,
                                )
                            if (!claimed) {
                                return@withSerializedQueueScheduling false
                            }
                        }
                    }

                    enqueueWorkForStatus(
                        recordingId = recordingId,
                        status = status,
                        executionToken = executionToken,
                        correlationId = ReliabilityEventLogger.newCorrelationId("queue-manual-recovery"),
                        routedRecording = routedRecording,
                    )
                    true
                }
            if (!scheduled) {
                return ManualRecoveryResult.NOT_RECOVERABLE_STATE
            }

            if (status == RecordingStatus.PENDING_TRANSCRIPTION &&
                TranscriptionEngine.fromId(routedRecording?.transcriptionEngineId) ==
                TranscriptionEngine.LOCAL_PARAKEET
            ) {
                readinessGate.verifyIfNeeded(VerificationTrigger.RECOVERY)
            }

            return ManualRecoveryResult.ENQUEUED
        }

        private fun enqueueWorkForStatus(
            recordingId: UUID,
            status: RecordingStatus,
            executionToken: String,
            correlationId: String,
            routedRecording: Recording?,
        ): String =
            when (status) {
                RecordingStatus.PENDING_ENHANCEMENT ->
                    workScheduler.enqueueEnhancement(
                        recordingId = recordingId,
                        executionToken = executionToken,
                        correlationId = correlationId,
                    )

                else ->
                    workScheduler.enqueueTranscription(
                        recordingId = recordingId,
                        executionToken = executionToken,
                        correlationId = correlationId,
                        requiresNetwork =
                            routedRecording?.requiresNetworkForTranscription() == true,
                    )
            }

        private suspend fun resolveTranscriptionRoute(recordingId: UUID): Recording? {
            val recording = recordingRepository.getRecording(recordingId) ?: return null
            return if (recording.transcriptionEngineId == null) {
                val defaultEngine = transcriptionRoutingStore.getSelectedEngine()
                recordingRepository.stampTranscriptionEngineIfUnset(recordingId, defaultEngine.id)
            } else {
                recording
            }
        }

        private suspend fun List<Recording>.anyLocalTranscription(): Boolean {
            for (recording in this) {
                if (recording.status == RecordingStatus.PENDING_TRANSCRIPTION) {
                    val routedRecording = resolveTranscriptionRoute(recording.id)
                    if (TranscriptionEngine.fromId(routedRecording?.transcriptionEngineId) ==
                        TranscriptionEngine.LOCAL_PARAKEET
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        private fun String?.isForegroundServicePolicyFailure(): Boolean {
            if (this == null) return false
            return contains("startForegroundService() not allowed") ||
                contains("ForegroundServiceStartNotAllowedException") ||
                contains("InvalidForegroundServiceTypeException") ||
                contains("androidx.work.impl.foreground.SystemForegroundService")
        }
    }
