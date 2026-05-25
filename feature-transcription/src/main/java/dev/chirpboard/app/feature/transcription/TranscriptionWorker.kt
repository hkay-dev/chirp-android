package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.transcription.audio.AudioDecoder
import dev.chirpboard.app.feature.transcription.audio.ChunkedAudioProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
class TranscriptionWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val recordingRepository: RecordingRepository,
        private val wordReplacementRepository: WordReplacementRepository,
        private val wordReplacer: WordReplacer,
        private val llmClient: LlmClient,
        private val transcriberProvider: TranscriberProvider,
        private val audioDecoder: AudioDecoder,
        private val recordingStateManager: dev.chirpboard.app.core.recording.RecordingStateManager,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "TranscriptionWorker"
            const val INPUT_RECORDING_ID = "recording_id"
            const val OUTPUT_TRANSCRIPT_ID = "transcript_id"
            const val OUTPUT_ERROR = "error"
        }

        override suspend fun doWork(): Result {
            val recordingIdString =
                inputData.getString(INPUT_RECORDING_ID)
                    ?: return buildTranscriptionFailureResult("Missing recording ID")

            val recordingId =
                try {
                    UUID.fromString(recordingIdString)
                } catch (e: IllegalArgumentException) {
                    return buildTranscriptionFailureResult("Invalid recording ID format")
                }

            val correlationId =
                inputData.getString(TranscriptionWorkRequest.INPUT_CORRELATION_ID)
                    ?: ReliabilityEventLogger.newCorrelationId("transcription")

            return try {
                transcribeRecording(recordingId, correlationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleError(recordingId, correlationId, e)
            }
        }

        private suspend fun transcribeRecording(
            recordingId: UUID,
            correlationId: String,
        ): Result {
            // Fetch the recording
            val recording =
                recordingRepository.getRecording(recordingId)
                    ?: return buildTranscriptionFailureResult("Recording not found: $recordingId")

            // Defer work if a recording is currently active to prevent memory pressure from model loading
            if (recordingStateManager.state.value.isActive) {
                Log.w(TAG, "Recording is currently active. Waiting for it to finish before transcribing...")
                recordingStateManager.state.first { !it.isActive }
                Log.d(TAG, "Recording finished. Proceeding with transcription.")
            }

            if (recording.status == RecordingStatus.PENDING_ENHANCEMENT) {
                return runEnhancementOnly(recordingId, recording, correlationId)
            }

            // Update status to TRANSCRIBING
            recordingRepository.updateStatus(recordingId, RecordingStatus.TRANSCRIBING)
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.TRANSCRIPTION,
                outcome = ReliabilityOutcome.STARTED,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = "worker_started",
            )

            // Verify audio file exists
            val audioFile = File(recording.audioPath)
            if (!audioFile.exists()) {
                recordingRepository.updateStatusWithError(
                    recordingId,
                    RecordingStatus.FAILED,
                    "Audio file not found: ${recording.audioPath}",
                )
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = correlationId,
                    recordingId = recordingId,
                    reasonCode = "audio_missing",
                )
                return buildTranscriptionFailureResult("Audio file not found")
            }

            // Check if model is downloaded
            if (!transcriberProvider.isModelDownloaded()) {
                recordingRepository.updateStatusWithError(
                    recordingId,
                    RecordingStatus.FAILED,
                    "Model not downloaded. Please download the speech recognition model in Settings.",
                )
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = correlationId,
                    recordingId = recordingId,
                    reasonCode = "model_not_downloaded",
                )
                return buildTranscriptionFailureResult("Model not downloaded")
            }

            // Initialize the transcriber if needed
            if (!transcriberProvider.isReady()) {
                Log.d(TAG, "Initializing transcriber...")
                val initialized = transcriberProvider.initialize()
                if (!initialized) {
                    recordingRepository.updateStatusWithError(
                        recordingId,
                        RecordingStatus.FAILED,
                        "Failed to initialize speech recognition model",
                    )
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.TRANSCRIPTION,
                        outcome = ReliabilityOutcome.FAILURE,
                        correlationId = correlationId,
                        recordingId = recordingId,
                        reasonCode = "model_init_failed",
                    )
                    return buildTranscriptionFailureResult("Failed to initialize model")
                }
            }

            // Decode and transcribe using chunked processing for memory efficiency
            // This uses 30-second chunks with 2-second overlap to prevent word truncation
            // Peak memory: ~4MB instead of ~76MB for a 10-minute recording
            Log.d(TAG, "Decoding and transcribing audio file: ${recording.audioPath}")

            val detailedTranscription: dev.chirpboard.app.feature.transcription.audio.JoinedChunkTranscription
            try {
                checkMemoryPressure()

                val processor =
                    ChunkedAudioProcessor(
                        chunkDurationMs = 30_000,
                        overlapDurationMs = 2_000,
                        sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                    )

                val audioFlow = audioDecoder.decodeAsFlow(recording.audioPath)

                detailedTranscription =
                    processor.processAndJoinDetailed(audioFlow) { samples ->
                        if (recordingStateManager.state.value.isActive) {
                            Log.w(TAG, "Recording started during transcription. Pausing transcription until recording finishes...")
                            recordingStateManager.state.first { !it.isActive }
                            Log.d(TAG, "Recording finished. Resuming transcription.")
                        }

                        if (!transcriberProvider.isReady()) {
                            Log.d(TAG, "Re-initializing transcriber...")
                            transcriberProvider.initialize()
                        }

                        mapOutcomeForChunkTranscription(
                            transcriberProvider.transcribe(
                                samples,
                                AudioDecoder.TARGET_SAMPLE_RATE,
                            ),
                        )
                    }

                Log.d(TAG, "Chunked transcription completed")
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory during transcription", e)
                recordingRepository.updateStatusWithError(
                    recordingId,
                    RecordingStatus.FAILED,
                    "Out of memory during transcription. Recording may be too long.",
                )
                return buildTranscriptionFailureResult("Out of memory during transcription")
            } catch (e: java.io.IOException) {
                Log.e(TAG, "I/O error during decode/transcription (may be retried)", e)
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to decode/transcribe audio file", e)
                throw e
            }

            val rawTranscriptionText = detailedTranscription.text
            if (rawTranscriptionText.isBlank()) {
                Log.w(TAG, "Transcription returned empty result")
            }
            Log.d(TAG, "Transcription result: ${rawTranscriptionText.take(100)}...")

            val enabledReplacements = wordReplacementRepository.getEnabledReplacements()
            val processedText = wordReplacer.apply(rawTranscriptionText, enabledReplacements)

            val transcript =
                Transcript(
                    recordingId = recordingId,
                    rawText = rawTranscriptionText,
                )
            val timings =
                detailedTranscription.wordTimings
                    ?.mapIndexed { index, timing ->
                        TranscriptTiming(
                            recordingId = recordingId,
                            sequenceIndex = index,
                            text = timing.text,
                            startOffsetMs = timing.startTimestampMs,
                            endOffsetMs = timing.endTimestampMs,
                        )
                    }.orEmpty()
            recordingRepository.saveTranscriptWithTiming(transcript, timings)

            applyEnhancement(recordingId, recording.source, processedText, correlationId)

            // Mark as completed
            recordingRepository.updateStatus(recordingId, RecordingStatus.COMPLETED)
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.TRANSCRIPTION,
                outcome = ReliabilityOutcome.SUCCESS,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = "worker_completed",
            )

            return buildTranscriptionSuccessResult(transcript.id)
        }

        private suspend fun runEnhancementOnly(
            recordingId: UUID,
            recording: dev.chirpboard.app.data.entity.Recording,
            correlationId: String,
        ): Result {
            val transcript =
                recordingRepository.getTranscript(recordingId)
                    ?: run {
                        val errorMessage = "No transcript found for enhancement"
                        recordingRepository.updateStatusWithError(
                            recordingId,
                            RecordingStatus.FAILED,
                            errorMessage,
                        )
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.ENHANCEMENT,
                            outcome = ReliabilityOutcome.FAILURE,
                            correlationId = correlationId,
                            recordingId = recordingId,
                            reasonCode = "enhancement_missing_transcript",
                            message = errorMessage,
                        )
                        return buildTranscriptionFailureResult(errorMessage)
                    }

            val enabledReplacements = wordReplacementRepository.getEnabledReplacements()
            val processedText =
                transcript.processedText
                    ?: wordReplacer.apply(transcript.rawText, enabledReplacements)

            recordingRepository.updateProcessedText(recordingId, processedText, "word_replacement")
            applyEnhancement(recordingId, recording.source, processedText, correlationId)

            recordingRepository.updateStatus(recordingId, RecordingStatus.COMPLETED)
            return Result.success(
                Data
                    .Builder()
                    .putString(OUTPUT_TRANSCRIPT_ID, transcript.id.toString())
                    .build(),
            )
        }

        private suspend fun applyEnhancement(
            recordingId: UUID,
            source: dev.chirpboard.app.data.model.RecordingSource,
            processedText: String,
            correlationId: String,
        ) {
            recordingRepository.updateStatus(recordingId, RecordingStatus.ENHANCING)
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.ENHANCEMENT,
                outcome = ReliabilityOutcome.STARTED,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = "enhancement_started",
            )

            try {
                val titleResult = llmClient.generateTitle(processedText)
                if (titleResult.isFailure) {
                    val e = titleResult.exceptionOrNull() ?: Exception("Failed to generate title")
                    Log.e(TAG, "Failed to generate title", e)
                    throw e
                }
                recordingRepository.updateTitle(recordingId, titleResult.getOrThrow())

                val summaryResult = llmClient.generateSummary(processedText)
                if (summaryResult.isFailure) {
                    val e = summaryResult.exceptionOrNull() ?: Exception("Failed to generate summary")
                    Log.e(TAG, "Failed to generate summary", e)
                    throw e
                }
                recordingRepository.updateSummary(recordingId, summaryResult.getOrThrow())

                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.ENHANCEMENT,
                    outcome = ReliabilityOutcome.SUCCESS,
                    correlationId = correlationId,
                    recordingId = recordingId,
                    reasonCode = "enhancement_applied",
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.ENHANCEMENT,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = correlationId,
                    recordingId = recordingId,
                    reasonCode = "enhancement_failed",
                    message = e.message,
                )
                throw e
            }
        }

        private fun showTranscriptionErrorNotification(recordingId: UUID, errorMessage: String) {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "transcription_errors"
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Transcription Errors",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
            }
            
            val notification = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Transcription Failed")
                .setContentText(errorMessage)
                .setAutoCancel(true)
                .build()
                
            notificationManager.notify(recordingId.hashCode(), notification)
        }

        private suspend fun handleError(
            recordingId: UUID,
            correlationId: String,
            exception: Exception,
        ): Result {
            val errorMessage = exception.message ?: "Unknown transcription error"
            val disposition =
                resolveWorkerFailureDisposition(
                    exception = exception,
                    runAttemptCount = runAttemptCount,
                    maxRetryCount = TRANSCRIPTION_MAX_RETRY_COUNT,
                )

            ReliabilityEventLogger.log(
                stage = ReliabilityStage.TRANSCRIPTION,
                outcome = ReliabilityOutcome.FAILURE,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = "worker_exception",
                message = errorMessage,
            )

            try {
                recordingRepository.updateStatusWithError(
                    recordingId,
                    disposition.status,
                    errorMessage,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to persist transcription error state", e)
                runCatching {
                    recordingRepository.updateStatus(recordingId, disposition.status)
                }.onFailure { fallbackError ->
                    if (fallbackError is CancellationException) throw fallbackError
                    Log.e(TAG, "Failed to persist fallback transcription status", fallbackError)
                }
            }

            return if (disposition.retry) {
                Result.retry()
            } else {
                showTranscriptionErrorNotification(recordingId, errorMessage)
                buildTranscriptionFailureResult(errorMessage)
            }
        }

        /**
         * Check memory pressure and log a warning if usage is high.
         * This is informational only - the chunked processor handles memory efficiency.
         *
         * @return true if memory pressure is high (>85% usage)
         */
        private fun checkMemoryPressure(): Boolean {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsagePercent = usedMemory.toFloat() / maxMemory.toFloat()

            return if (memoryUsagePercent > 0.85f) {
                Log.w(
                    TAG,
                    "High memory pressure before transcription: ${(memoryUsagePercent * 100).toInt()}% " +
                        "(${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)",
                )
                true
            } else {
                Log.d(
                    TAG,
                    "Memory usage: ${(memoryUsagePercent * 100).toInt()}% " +
                        "(${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)",
                )
                false
            }
        }
    }
