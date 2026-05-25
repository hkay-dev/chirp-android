package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.TranscriptionQueueLifecycle
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.TranscriptionScheduler
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
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
        @ApplicationContext private val context: Context,
        private val recordingRepository: RecordingRepository,
        private val constraintChecker: WorkConstraintChecker,
        private val transcriberProvider: dev.chirpboard.app.core.transcription.TranscriberProvider,
        private val readinessGate: SpeechModelReadinessGate,
    ) : TranscriptionRecovery, TranscriptionQueueLifecycle {
        private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
        private val reconciliationMutex = Mutex()
        private var reconciliationJob: Job? = null

        @Volatile
        private var reconciliationStarted = false
        private val queueReconciler by lazy {
            TranscriptionQueueReconciler(
                context = context,
                recordingRepository = recordingRepository,
                constraintChecker = constraintChecker,
                setConstraintWarning = { _constraintWarning.value = it },
                setActiveCount = { _activeCount.value = it },
            )
        }

        companion object {
            private const val TAG = "TranscriptionQueueMgr"
            private const val DEFAULT_RECONCILIATION_INTERVAL_MS = 60_000L
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
         * Start periodic queue reconciliation while the app process is alive.
         * Safe to call multiple times; only the first call starts the loop.
         */
        override fun startContinuousReconciliation(scope: CoroutineScope) {
            startContinuousReconciliation(scope, DEFAULT_RECONCILIATION_INTERVAL_MS)
        }

        fun startContinuousReconciliation(
            scope: CoroutineScope,
            intervalMs: Long,
        ) {
            synchronized(this) {
                if (reconciliationStarted) return
                reconciliationStarted = true
            }

            reconciliationJob =
                scope.launch {
                    while (isActive) {
                        try {
                            reconciliationMutex.withLock {
                                queueReconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e(TAG, "Periodic queue reconciliation failed", e)
                        }
                        delay(intervalMs)
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

            ReliabilityEventLogger.log(
                stage = ReliabilityStage.QUEUE_ENQUEUE,
                outcome = ReliabilityOutcome.STARTED,
                correlationId = corrId,
                recordingId = recordingId,
                reasonCode = "enqueue_requested",
            )

            // Check constraints and warn user (but still enqueue - WorkManager will wait)
            val status = constraintChecker.checkConstraints()
            _constraintWarning.value = constraintChecker.getConstraintMessage(status)

            // Update status to pending and clear stale error metadata
            recordingRepository.updateStatusWithError(
                id = recordingId,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                errorMessage = null,
            )

            try {
                // Schedule the work
                val workId = TranscriptionWorkRequest.enqueue(context, recordingId, corrId)
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.QUEUE_ENQUEUE,
                    outcome = ReliabilityOutcome.SUCCESS,
                    correlationId = corrId,
                    recordingId = recordingId,
                    reasonCode = "enqueue_scheduled",
                )
                return workId
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.QUEUE_ENQUEUE,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = corrId,
                    recordingId = recordingId,
                    reasonCode = "enqueue_exception",
                    message = e.message,
                )
                throw e
            }
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

            ReliabilityEventLogger.log(
                stage = ReliabilityStage.QUEUE_ENQUEUE,
                outcome = ReliabilityOutcome.RECOVERED,
                correlationId = ReliabilityEventLogger.newCorrelationId("queue-recovery"),
                recordingId = recordingId,
                reasonCode = "pending_for_recovery",
                message = reason,
            )
        }

        /**
         * Retry a failed transcription.
         * Resets status from FAILED to PENDING_TRANSCRIPTION and re-enqueues.
         *
         * Checks device constraints and emits a warning via [constraintWarning] if
         * battery is low or storage is insufficient.
         *
         * @param recordingId The UUID of the recording to retry
         */
        override suspend fun retry(recordingId: UUID) {
            val recording = recordingRepository.getRecording(recordingId)

            if (recording?.status == RecordingStatus.FAILED) {
                // Check constraints and warn user
                val status = constraintChecker.checkConstraints()
                _constraintWarning.value = constraintChecker.getConstraintMessage(status)

                // Clear error and reset status
                recordingRepository.updateStatusWithError(
                    id = recordingId,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = null,
                )

                // Re-enqueue for processing
                TranscriptionWorkRequest.enqueue(
                    context,
                    recordingId,
                    ReliabilityEventLogger.newCorrelationId("queue-retry"),
                )
            }
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

        suspend fun recoverPendingEnhancement(recordingId: UUID): ManualRecoveryResult {
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

        override suspend fun getRecoveryDiagnostics(recordingId: UUID): RecoveryDiagnostics =
            reconciliationMutex.withLock {
                queueReconciler.getRecoveryDiagnostics(recordingId)
            }

        override suspend fun recoverRecordingsWaitingForModel() {
            val failed = recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED).first().value
            failed.filter {
                it.errorMessage?.startsWith("Model not downloaded") == true ||
                it.errorMessage?.startsWith("Failed to initialize") == true ||
                it.errorMessage?.startsWith("Speech model unavailable") == true ||
                it.errorMessage?.startsWith("Recognizer not ready") == true
            }.forEach { recording ->
                retry(recording.id)
            }
        }

        /**
         * Cancel pending transcription.
         * Cancels WorkManager work and updates status.
         *
         * @param recordingId The UUID of the recording to cancel
         */
        suspend fun cancel(recordingId: UUID) {
            val recording = recordingRepository.getRecording(recordingId)

            if (recording != null) {
                // Cancel any pending work for this recording
                workManager.cancelUniqueWork(TranscriptionWorkRequest.workName(recordingId))

                // Mark as FAILED so it doesn't get automatically restarted by the reconciler
                when (recording.status) {
                    RecordingStatus.TRANSCRIBING, RecordingStatus.PENDING_TRANSCRIPTION -> {
                        recordingRepository.updateStatusWithError(recordingId, RecordingStatus.FAILED, "Cancelled by user")
                    }

                    else -> {
                        // For other statuses, don't change
                    }
                }
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
            if (transcriberProvider.isModelDownloaded()) {
                recoverRecordingsWaitingForModel()
            }
        }

        private suspend fun enqueueManualRecovery(
            recordingId: UUID,
            status: RecordingStatus,
            reason: String,
        ): ManualRecoveryResult {
            val ownership = reconciliationMutex.withLock { queueReconciler.inspectQueueOwnership(recordingId) }
            val blockResult = blockedManualRecoveryResult(ownership)
            if (blockResult != null) {
                return blockResult
            }

            val statusCheck = constraintChecker.checkConstraints()
            _constraintWarning.value = constraintChecker.getConstraintMessage(statusCheck)

            recordingRepository.updateStatusWithError(
                id = recordingId,
                status = status,
                errorMessage = buildManualRecoveryMessage(reason),
            )

            TranscriptionWorkRequest.enqueue(
                context,
                recordingId,
                ReliabilityEventLogger.newCorrelationId("queue-manual-recovery"),
            )

            return ManualRecoveryResult.ENQUEUED
        }
    }
