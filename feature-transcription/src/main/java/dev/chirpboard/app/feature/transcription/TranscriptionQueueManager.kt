package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
class TranscriptionQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingRepository: RecordingRepository,
    private val constraintChecker: WorkConstraintChecker
) {
    
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    
    companion object {
        private const val TAG = "TranscriptionQueueMgr"
        private const val RECOVERABLE_QUEUE_HANDOFF_PREFIX = "recoverable_queue_handoff:"
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
     * Flow of recordings pending transcription.
     * Emits updates whenever the pending queue changes.
     */
    val pendingRecordings: Flow<List<Recording>> =
        recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION)
    
    /**
     * Number of recordings currently being processed (TRANSCRIBING status).
     */
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()
    
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
    suspend fun enqueue(recordingId: UUID) {
        // Check constraints and warn user (but still enqueue - WorkManager will wait)
        val status = constraintChecker.checkConstraints()
        _constraintWarning.value = constraintChecker.getConstraintMessage(status)
        
        // Update status to pending and clear stale error metadata
        recordingRepository.updateStatusWithError(
            id = recordingId,
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            errorMessage = null
        )
        
        // Schedule the work
        TranscriptionWorkRequest.enqueue(context, recordingId)
    }

    /**
     * Mark a recording as recoverable pending when save succeeded but enqueue failed.
     * Startup recovery can use this marker to prioritize queue reattachment.
     */
    suspend fun markPendingForQueueRecovery(
        recordingId: UUID,
        reason: String,
        cause: Throwable? = null
    ) {
        val causeMessage = cause?.message?.takeIf { it.isNotBlank() }
        val errorMessage = if (causeMessage != null) {
            "$RECOVERABLE_QUEUE_HANDOFF_PREFIX$reason Cause: $causeMessage"
        } else {
            "$RECOVERABLE_QUEUE_HANDOFF_PREFIX$reason"
        }

        recordingRepository.updateStatusWithError(
            id = recordingId,
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            errorMessage = errorMessage
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
    suspend fun retry(recordingId: UUID) {
        val recording = recordingRepository.getRecording(recordingId)
        
        if (recording?.status == RecordingStatus.FAILED) {
            // Check constraints and warn user
            val status = constraintChecker.checkConstraints()
            _constraintWarning.value = constraintChecker.getConstraintMessage(status)
            
            // Clear error and reset status
            recordingRepository.updateStatusWithError(
                id = recordingId,
                status = RecordingStatus.PENDING_TRANSCRIPTION,
                errorMessage = null
            )
            
            // Re-enqueue for processing
            TranscriptionWorkRequest.enqueue(context, recordingId)
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
            
            // If it was actively transcribing, mark as pending so it can be retried
            // If it was just pending, keep it pending
            when (recording.status) {
                RecordingStatus.TRANSCRIBING -> {
                    recordingRepository.updateStatus(recordingId, RecordingStatus.PENDING_TRANSCRIPTION)
                }
                RecordingStatus.PENDING_TRANSCRIPTION -> {
                    // Already pending, nothing to change
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
    suspend fun processPendingOnStartup() {
        Log.i(TAG, "Starting transcription recovery on app startup")
        
        // Step 1: Recover stuck TRANSCRIBING recordings
        // These are recordings where the app was killed during transcription
        val stuckRecordings = recordingRepository
            .getRecordingsByStatus(RecordingStatus.TRANSCRIBING)
            .first()
        
        if (stuckRecordings.isNotEmpty()) {
            Log.w(TAG, "Recovering ${stuckRecordings.size} stuck recording(s) from previous session")
            stuckRecordings.forEach { recording ->
                Log.d(TAG, "Recovering stuck recording: ${recording.id}")
                recordingRepository.updateStatus(
                    recording.id,
                    RecordingStatus.PENDING_TRANSCRIPTION
                )
            }
            Log.i(TAG, "Recovery complete: ${stuckRecordings.size} recording(s) reset to pending")
        }
        
        // Step 2: Process all pending recordings
        val pending = pendingRecordings.first()
        Log.d(TAG, "Found ${pending.size} pending recording(s) to process")
        
        pending.forEach { recording ->
            // Check if work is already scheduled/running for this recording
            val workInfos = workManager
                .getWorkInfosForUniqueWork(TranscriptionWorkRequest.workName(recording.id))
                .get()
            
            val needsScheduling = workInfos.isEmpty() || workInfos.all { workInfo ->
                workInfo.state == WorkInfo.State.CANCELLED ||
                workInfo.state == WorkInfo.State.FAILED
            }
            
            if (needsScheduling) {
                Log.d(TAG, "Scheduling transcription work for recording: ${recording.id}")
                try {
                    TranscriptionWorkRequest.enqueue(context, recording.id)
                    if (recording.hasRecoverableQueueHandoffError()) {
                        recordingRepository.updateStatusWithError(
                            id = recording.id,
                            status = RecordingStatus.PENDING_TRANSCRIPTION,
                            errorMessage = null
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to schedule pending recording ${recording.id}", e)
                }
            } else if (recording.hasRecoverableQueueHandoffError()) {
                // Recovery marker can be cleared once ownership is confirmed
                recordingRepository.updateStatusWithError(
                    id = recording.id,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    errorMessage = null
                )
            }
        }
        
        // Update active count based on currently running work
        updateActiveCount()
        
        // Check constraints and warn if there are pending items but constraints aren't met
        if (pending.isNotEmpty()) {
            val status = constraintChecker.checkConstraints()
            _constraintWarning.value = constraintChecker.getConstraintMessage(status)
        }
        
        Log.i(TAG, "Startup processing complete")
    }
    
    /**
     * Update the active count by querying recordings with TRANSCRIBING status.
     */
    private suspend fun updateActiveCount() {
        val transcribing = recordingRepository
            .getRecordingsByStatus(RecordingStatus.TRANSCRIBING)
            .first()
        _activeCount.value = transcribing.size
    }

    private fun Recording.hasRecoverableQueueHandoffError(): Boolean {
        return errorMessage?.startsWith(RECOVERABLE_QUEUE_HANDOFF_PREFIX) == true
    }
}
