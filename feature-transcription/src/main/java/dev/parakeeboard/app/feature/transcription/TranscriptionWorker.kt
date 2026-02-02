package dev.parakeeboard.app.feature.transcription

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.parakeeboard.app.data.entity.Transcript
import dev.parakeeboard.app.data.model.RecordingStatus
import dev.parakeeboard.app.data.repository.RecordingRepository
import dev.parakeeboard.app.data.repository.WordReplacementRepository
import java.io.File
import java.util.UUID

/**
 * Worker that handles transcription of audio recordings.
 * 
 * Takes a recording ID as input, processes the audio file, creates a transcript,
 * and updates the recording status accordingly.
 * 
 * Currently uses a placeholder implementation - Sherpa-ONNX integration pending.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val wordReplacementRepository: WordReplacementRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val recordingIdString = inputData.getString(INPUT_RECORDING_ID)
            ?: return Result.failure(
                Data.Builder()
                    .putString(OUTPUT_ERROR, "Missing recording ID")
                    .build()
            )

        val recordingId = try {
            UUID.fromString(recordingIdString)
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                Data.Builder()
                    .putString(OUTPUT_ERROR, "Invalid recording ID format")
                    .build()
            )
        }

        return try {
            transcribeRecording(recordingId)
        } catch (e: Exception) {
            handleError(recordingId, e)
        }
    }

    private suspend fun transcribeRecording(recordingId: UUID): Result {
        // Fetch the recording
        val recording = recordingRepository.getRecording(recordingId)
            ?: return Result.failure(
                Data.Builder()
                    .putString(OUTPUT_ERROR, "Recording not found: $recordingId")
                    .build()
            )

        // Update status to TRANSCRIBING
        recordingRepository.updateStatus(recordingId, RecordingStatus.TRANSCRIBING)

        // Verify audio file exists
        val audioFile = File(recording.audioPath)
        if (!audioFile.exists()) {
            recordingRepository.updateStatusWithError(
                recordingId,
                RecordingStatus.FAILED,
                "Audio file not found: ${recording.audioPath}"
            )
            return Result.failure(
                Data.Builder()
                    .putString(OUTPUT_ERROR, "Audio file not found")
                    .build()
            )
        }

        // TODO: Integrate with Sherpa-ONNX for actual transcription
        // For now, create a placeholder transcript
        val rawTranscriptionText = "Transcription placeholder - Sherpa integration pending"

        // Apply word replacements
        val processedText = wordReplacementRepository.applyReplacements(rawTranscriptionText)

        // Create and save transcript
        val transcript = Transcript(
            recordingId = recordingId,
            rawText = processedText
        )
        recordingRepository.saveTranscript(transcript)

        // Update recording status to pending enhancement
        // In a full implementation, check if LLM processing is configured
        // For now, always go to PENDING_ENHANCEMENT
        recordingRepository.updateStatus(recordingId, RecordingStatus.PENDING_ENHANCEMENT)

        return Result.success(
            Data.Builder()
                .putString(OUTPUT_TRANSCRIPT_ID, transcript.id.toString())
                .build()
        )
    }

    private suspend fun handleError(recordingId: UUID, exception: Exception): Result {
        val errorMessage = exception.message ?: "Unknown transcription error"

        // Try to update the recording status to FAILED
        try {
            recordingRepository.updateStatusWithError(
                recordingId,
                RecordingStatus.FAILED,
                errorMessage
            )
        } catch (e: Exception) {
            // If we can't update the status, just log and continue
            // The recording will remain in TRANSCRIBING state
        }

        // Check if this is a retryable error
        return if (shouldRetry(exception) && runAttemptCount < MAX_RETRY_COUNT) {
            Result.retry()
        } else {
            Result.failure(
                Data.Builder()
                    .putString(OUTPUT_ERROR, errorMessage)
                    .build()
            )
        }
    }

    private fun shouldRetry(exception: Exception): Boolean {
        // Retry on transient errors (e.g., file system busy)
        // Don't retry on permanent errors (e.g., file not found, invalid format)
        return when (exception) {
            is java.io.IOException -> true
            is OutOfMemoryError -> false
            else -> false
        }
    }

    companion object {
        const val INPUT_RECORDING_ID = "recording_id"
        const val OUTPUT_TRANSCRIPT_ID = "transcript_id"
        const val OUTPUT_ERROR = "error"

        private const val MAX_RETRY_COUNT = 3
    }
}
