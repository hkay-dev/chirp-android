package dev.chirpboard.app.feature.transcription

import android.content.Context
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
    private val recordingRepository: RecordingRepository
) {
    
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    
    private val _activeCount = MutableStateFlow(0)
    
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
     * @param recordingId The UUID of the recording to transcribe
     */
    suspend fun enqueue(recordingId: UUID) {
        // Update status to pending
        recordingRepository.updateStatus(recordingId, RecordingStatus.PENDING_TRANSCRIPTION)
        
        // Schedule the work
        TranscriptionWorkRequest.enqueue(context, recordingId)
    }
    
    /**
     * Retry a failed transcription.
     * Resets status from FAILED to PENDING_TRANSCRIPTION and re-enqueues.
     * 
     * @param recordingId The UUID of the recording to retry
     */
    suspend fun retry(recordingId: UUID) {
        val recording = recordingRepository.getRecording(recordingId)
        
        if (recording?.status == RecordingStatus.FAILED) {
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
     * Process all pending recordings on app startup.
     * Call this from Application.onCreate or a startup initializer.
     * 
     * Queries all PENDING_TRANSCRIPTION recordings and ensures each
     * has a WorkManager job scheduled.
     */
    suspend fun processPendingOnStartup() {
        val pending = pendingRecordings.first()
        
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
                TranscriptionWorkRequest.enqueue(context, recording.id)
            }
        }
        
        // Update active count based on currently running work
        updateActiveCount()
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
}
