package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.llm.LlmProcessor
import dev.chirpboard.app.feature.transcription.audio.AudioDecoder
import java.io.File
import java.util.UUID

/**
 * Worker that handles transcription of audio recordings.
 * 
 * Takes a recording ID as input, processes the audio file using Sherpa-ONNX,
 * creates a transcript, and updates the recording status accordingly.
 * 
 * Uses AudioDecoder to convert M4A audio to PCM samples at 16kHz,
 * then TranscriberProvider (backed by Sherpa-ONNX) to transcribe to text.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val wordReplacementRepository: WordReplacementRepository,
    private val wordReplacer: WordReplacer,
    private val llmProcessor: LlmProcessor,
    private val transcriberProvider: TranscriberProvider,
    private val audioDecoder: AudioDecoder
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TranscriptionWorker"
        const val INPUT_RECORDING_ID = "recording_id"
        const val OUTPUT_TRANSCRIPT_ID = "transcript_id"
        const val OUTPUT_ERROR = "error"

        private const val MAX_RETRY_COUNT = 3
    }

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

        // Check if model is downloaded
        if (!transcriberProvider.isModelDownloaded()) {
            recordingRepository.updateStatusWithError(
                recordingId,
                RecordingStatus.FAILED,
                "Model not downloaded. Please download the speech recognition model in Settings."
            )
            return Result.failure(
                Data.Builder()
                    .putString(OUTPUT_ERROR, "Model not downloaded")
                    .build()
            )
        }

        // Initialize the transcriber if needed
        if (!transcriberProvider.isReady()) {
            Log.d(TAG, "Initializing transcriber...")
            val initialized = transcriberProvider.initialize()
            if (!initialized) {
                recordingRepository.updateStatusWithError(
                    recordingId,
                    RecordingStatus.FAILED,
                    "Failed to initialize speech recognition model"
                )
                return Result.failure(
                    Data.Builder()
                        .putString(OUTPUT_ERROR, "Failed to initialize model")
                        .build()
                )
            }
        }

        // Decode audio file and transcribe
        Log.d(TAG, "Decoding audio file: ${recording.audioPath}")
        val allSamples = mutableListOf<Float>()
        try {
            audioDecoder.decode(recording.audioPath) { chunk ->
                allSamples.addAll(chunk.toList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio file", e)
            recordingRepository.updateStatusWithError(
                recordingId,
                RecordingStatus.FAILED,
                "Failed to decode audio: ${e.message}"
            )
            return Result.failure(
                Data.Builder()
                    .putString(OUTPUT_ERROR, "Failed to decode audio: ${e.message}")
                    .build()
            )
        }

        // Transcribe the audio samples
        Log.d(TAG, "Transcribing ${allSamples.size} samples...")
        val rawTranscriptionText = transcriberProvider.transcribe(
            allSamples.toFloatArray(),
            AudioDecoder.TARGET_SAMPLE_RATE
        )
        
        if (rawTranscriptionText.isBlank()) {
            Log.w(TAG, "Transcription returned empty result")
            // Continue with empty text - it might be a silent recording
        }
        Log.d(TAG, "Transcription result: ${rawTranscriptionText.take(100)}...")

        // Apply word replacements to get processed text for LLM
        val enabledReplacements = wordReplacementRepository.getEnabledReplacements()
        val processedText = wordReplacer.apply(rawTranscriptionText, enabledReplacements)

        // Create and save transcript with raw text (before replacements)
        // The processed text (after replacements) is used for LLM processing
        val transcript = Transcript(
            recordingId = recordingId,
            rawText = rawTranscriptionText
        )
        recordingRepository.saveTranscript(transcript)

        // LLM processing for title and summary
        recordingRepository.updateStatus(recordingId, RecordingStatus.ENHANCING)
        
        try {
            val llmResult = llmProcessor.process(processedText, recording.source)
            
            // Update title if generated (only for APP/WIDGET sources)
            llmResult.title?.let { generatedTitle ->
                recordingRepository.updateTitle(recordingId, generatedTitle)
            }
            
            // Update summary if generated
            llmResult.summary?.let { generatedSummary ->
                recordingRepository.updateSummary(recordingId, generatedSummary)
            }
        } catch (e: Exception) {
            // LLM processing failed - continue without title/summary
            // Recording will keep its default title
        }

        // Mark as completed
        recordingRepository.updateStatus(recordingId, RecordingStatus.COMPLETED)

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
}
