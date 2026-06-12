package dev.chirpboard.app.feature.transcription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.model.RecordingEnhancementIntent
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.transcription.audio.AudioDecoder
import dev.chirpboard.app.feature.transcription.audio.ChunkedAudioProcessor
import kotlinx.coroutines.CancellationException
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
 *
 * Long-audio execution (PIPE-01): the worker promotes itself to dataSync foreground work
 * so multi-hour decodes are not killed at the ~10-minute background job window. When the
 * platform refuses the foreground start the run continues in the background; if it is then
 * stopped at the window, decoding restarts from sample 0 on the next attempt (resume
 * design note: per-chunk transcript checkpointing keyed on the execution token would allow
 * a restart to resume mid-recording — the chunked processor already exposes chunk start
 * offsets — but is deliberately not implemented yet to keep the commit path single-shot).
 */
@HiltWorker
class TranscriptionWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val recordingRepository: RecordingRepository,
        private val profileRepository: ProfileRepository,
        private val wordReplacementRepository: WordReplacementRepository,
        private val wordReplacer: WordReplacer,
        private val textEnhancement: RecordingTextEnhancementPort,
        private val transcriberProvider: TranscriberProvider,
        private val audioDecoder: AudioDecoder,
        private val recordingStateManager: dev.chirpboard.app.core.recording.RecordingStateManager,
        private val workScheduler: TranscriptionWorkScheduler,
        private val completionExporter: TranscriptionCompletionExporter,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "TranscriptionWorker"
            const val INPUT_RECORDING_ID = "recording_id"
            const val OUTPUT_TRANSCRIPT_ID = "transcript_id"
            const val OUTPUT_ERROR = "error"
            private const val TRANSCRIPTION_ERROR_GROUP = "transcription_error_group"
            private const val TRANSCRIPTION_ERROR_SUMMARY_NOTIFICATION_ID = 2003
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
            val executionToken =
                inputData.getString(TranscriptionWorkRequest.INPUT_EXECUTION_TOKEN)
                    ?: return buildTranscriptionFailureResult("Missing transcription execution token")

            return try {
                transcribeRecording(recordingId, correlationId, executionToken)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleError(recordingId, correlationId, executionToken, e)
            }
        }

        private suspend fun transcribeRecording(
            recordingId: UUID,
            correlationId: String,
            executionToken: String,
        ): Result {
            // Fetch the recording
            val recording =
                recordingRepository.getRecording(recordingId)
                    ?: return buildTranscriptionFailureResult("Recording not found: $recordingId")

            // PIPE-01: promote to dataSync foreground before the active-recording wait and the
            // decode, both of which can legitimately exceed the background execution window.
            trySetWorkerForeground(buildTranscriptionForegroundInfo(applicationContext), TAG)

            // Defer work if a recording is currently active to prevent memory pressure from model loading
            if (recordingStateManager.state.value.isActive) {
                Log.w(TAG, "Recording is currently active. Waiting for it to finish before transcribing...")
                waitForInactiveRecording(recordingId, correlationId, recording.durationMs)
                Log.d(TAG, "Recording finished. Proceeding with transcription.")
            }

            if (recording.status == RecordingStatus.PENDING_ENHANCEMENT) {
                logStaleTranscription(recordingId, correlationId, "worker_saw_enhancement_phase")
                return androidx.work.ListenableWorker.Result.success()
            }

            val ownedRecording =
                recordingRepository.beginTranscriptionExecution(recordingId, executionToken)
                    ?: run {
                        logStaleTranscription(recordingId, correlationId, "transcription_ownership_lost")
                        return androidx.work.ListenableWorker.Result.success()
                    }
            val transcriptionLog =
                ReliabilityEventLogger.scoped(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    correlationId = correlationId,
                    recordingId = recordingId,
                )
            transcriptionLog.started("worker_started")

            // Verify audio file exists
            // NOTE (I18N-06): the failTranscriptionExecution strings below are persisted machine
            // codes (classified by classifyRecordingProcessingNote) — do not reword them. User
            // copy comes from string resources at display time.
            val audioFile = File(ownedRecording.audioPath)
            if (!audioFile.exists()) {
                recordingRepository.failTranscriptionExecution(
                    recordingId,
                    executionToken,
                    RecordingStatus.FAILED,
                    "Audio file not found: ${ownedRecording.audioPath}",
                )
                transcriptionLog.failure("audio_missing")
                showTranscriptionErrorNotification(
                    recordingId,
                    applicationContext.getString(R.string.transcription_error_audio_missing),
                )
                return buildTranscriptionFailureResult("Audio file not found")
            }

            // Check if model is downloaded
            if (!transcriberProvider.isModelDownloaded()) {
                recordingRepository.failTranscriptionExecution(
                    recordingId,
                    executionToken,
                    RecordingStatus.FAILED,
                    "Model not downloaded. Please download the speech recognition model in Settings.",
                )
                transcriptionLog.failure("model_not_downloaded")
                showTranscriptionErrorNotification(
                    recordingId,
                    applicationContext.getString(R.string.transcription_error_model_missing),
                )
                return buildTranscriptionFailureResult("Model not downloaded")
            }

            // Initialize the transcriber if needed
            if (!transcriberProvider.isReady()) {
                Log.d(TAG, "Initializing transcriber...")
                val initialized = transcriberProvider.initialize()
                if (!initialized) {
                    transcriptionLog.failure("model_init_failed")
                    if (transcriberProvider.isModelDownloaded()) {
                        throw RetryableTranscriptionException(
                            "Failed to initialize speech recognition model",
                        )
                    }
                    recordingRepository.failTranscriptionExecution(
                        recordingId,
                        executionToken,
                        RecordingStatus.FAILED,
                        "Failed to initialize speech recognition model",
                    )
                    showTranscriptionErrorNotification(
                        recordingId,
                        applicationContext.getString(R.string.transcription_error_model_init),
                    )
                    return buildTranscriptionFailureResult("Failed to initialize model")
                }
            }

            // Decode and transcribe using chunked processing for memory efficiency
            // This uses 30-second chunks with 2-second overlap to prevent word truncation
            // Peak memory: ~4MB instead of ~76MB for a 10-minute recording
            Log.d(TAG, "Decoding and transcribing audio file: ${ownedRecording.audioPath}")

            val detailedTranscription: dev.chirpboard.app.feature.transcription.audio.JoinedChunkTranscription
            try {
                checkMemoryPressure()

                val processor =
                    ChunkedAudioProcessor(
                        chunkDurationMs = 30_000,
                        overlapDurationMs = 2_000,
                        sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                    )

                val audioFlow = audioDecoder.decodeAsFlow(ownedRecording.audioPath)

                detailedTranscription =
                    processor.processAndJoinDetailed(audioFlow) { samples ->
                        if (recordingStateManager.state.value.isActive) {
                            Log.w(TAG, "Recording started during transcription. Pausing transcription until recording finishes...")
                            waitForInactiveRecording(recordingId, correlationId, ownedRecording.durationMs)
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
                recordingRepository.failTranscriptionExecution(
                    recordingId,
                    executionToken,
                    RecordingStatus.FAILED,
                    "Out of memory during transcription. Recording may be too long.",
                )
                showTranscriptionErrorNotification(
                    recordingId,
                    applicationContext.getString(R.string.transcription_error_out_of_memory),
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
                    processedText = processedText,
                    processingMode = "word_replacement",
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
            val enhancementIntent = resolveEnhancementIntent(recordingId, ownedRecording, processedText, correlationId)
            val enhancementExecutionToken = enhancementIntent?.let { UUID.randomUUID().toString() }
            val committed =
                recordingRepository.commitTranscriptionResult(
                    transcript = transcript,
                    timings = timings,
                    enhancementIntent = enhancementIntent,
                    expectedExecutionToken = executionToken,
                    enhancementExecutionToken = enhancementExecutionToken,
                )
            if (!committed) {
                logStaleTranscription(recordingId, correlationId, "transcription_commit_stale")
                return androidx.work.ListenableWorker.Result.success()
            }

            val enhancementQueued =
                enhancementIntent != null &&
                    enhancementExecutionToken != null &&
                    enqueueEnhancement(recordingId, enhancementExecutionToken, correlationId)
            transcriptionLog.success(
                if (enhancementQueued) {
                    "worker_completed_pending_enhancement"
                } else {
                    "worker_completed"
                },
            )

            if (enhancementIntent == null) {
                // Terminal COMPLETED transition for recordings without enhancement work:
                // auto-export now. Recordings with enhancement export when the enhancement
                // worker resolves, so each pipeline run exports exactly once (PLH-3/ERR-5).
                completionExporter.exportIfCompleted(recordingId)
            }

            return buildTranscriptionSuccessResult(transcript.id)
        }

        private suspend fun resolveEnhancementIntent(
            recordingId: UUID,
            recording: dev.chirpboard.app.data.entity.Recording,
            processedText: String,
            correlationId: String,
        ): RecordingEnhancementIntent? {
            val profile = recording.profileId?.let { profileRepository.getProfile(it) }
            val policy =
                resolveRecordingEnhancementPolicy(
                    profile = profile,
                    globalAutoTitle = textEnhancement.defaultAutoTitleEnabled(),
                    globalAutoSummary = textEnhancement.defaultAutoSummaryEnabled(),
                )
            if (!policy.hasRequestedWork) {
                ReliabilityEventLogger
                    .scoped(ReliabilityStage.ENHANCEMENT, correlationId, recordingId)
                    .skipped("enhancement_not_requested")
                return null
            }

            val runtimeSnapshot = textEnhancement.runtimeSnapshot()
            val processingModeSnapshot =
                policy.processingModeId?.let { modeId ->
                    textEnhancement.resolveProcessingModeSnapshot(processedText, modeId)
                }

            return RecordingEnhancementIntent(
                processingModeId = processingModeSnapshot?.id ?: policy.processingModeId,
                processingModeLabel = processingModeSnapshot?.label,
                processingModeType = processingModeSnapshot?.type,
                processingModePrompt = processingModeSnapshot?.prompt,
                autoTitle = policy.autoTitle,
                autoSummary = policy.autoSummary,
                llmProviderId = runtimeSnapshot.providerId,
                llmModelId = runtimeSnapshot.modelId,
            )
        }

        private suspend fun enqueueEnhancement(
            recordingId: UUID,
            executionToken: String,
            correlationId: String,
        ): Boolean =
            try {
                workScheduler.enqueueEnhancement(recordingId, executionToken, correlationId)
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                recordingRepository.claimEnhancementExecution(
                    recordingId = recordingId,
                    executionToken = executionToken,
                    status = RecordingStatus.PENDING_ENHANCEMENT,
                    errorMessage = "${RECOVERABLE_QUEUE_HANDOFF_PREFIX}enhancement enqueue failed. Cause: ${e.message.orEmpty()}",
                )
                ReliabilityEventLogger
                    .scoped(ReliabilityStage.QUEUE_ENQUEUE, correlationId, recordingId)
                    .failure("enhancement_enqueue_failed", e)
                true
            }

        /**
         * PIPE-04: terminal-failure notification with a branded small icon, a tap action into
         * the app, and a group summary so a backlog failing on a shared root cause collapses
         * into one stack instead of dozens of loose notifications. Posted for every terminal
         * FAILED path so coverage is consistent; silently no-ops if POST_NOTIFICATIONS was
         * denied (the recording row still surfaces the FAILED state in-app).
         */
        private fun showTranscriptionErrorNotification(recordingId: UUID, errorMessage: String) {
            val context = applicationContext
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "transcription_errors"
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    context.getString(R.string.transcription_error_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                notificationManager.createNotificationChannel(channel)
            }

            val contentIntent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
                    PendingIntent.getActivity(
                        context,
                        recordingId.hashCode(),
                        launchIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notif_transcription)
                .setContentTitle(context.getString(R.string.transcription_error_notification_title))
                .setContentText(errorMessage)
                .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
                .setGroup(TRANSCRIPTION_ERROR_GROUP)
                .setAutoCancel(true)
                .apply { contentIntent?.let(::setContentIntent) }
                .build()
            notificationManager.notify(recordingId.hashCode(), notification)

            val groupSummary = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notif_transcription)
                .setContentTitle(context.getString(R.string.transcription_error_group_summary))
                .setGroup(TRANSCRIPTION_ERROR_GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .apply { contentIntent?.let(::setContentIntent) }
                .build()
            notificationManager.notify(TRANSCRIPTION_ERROR_SUMMARY_NOTIFICATION_ID, groupSummary)
        }

        private fun logStaleTranscription(
            recordingId: UUID,
            correlationId: String,
            reasonCode: String,
        ) {
            ReliabilityEventLogger
                .scoped(ReliabilityStage.TRANSCRIPTION, correlationId, recordingId)
                .skipped(reasonCode)
        }

        private suspend fun handleError(
            recordingId: UUID,
            correlationId: String,
            executionToken: String,
            exception: Exception,
        ): Result {
            val errorMessage = exception.message ?: "Unknown transcription error"
            val disposition =
                resolveWorkerFailureDisposition(
                    exception = exception,
                    runAttemptCount = runAttemptCount,
                    maxRetryCount = TRANSCRIPTION_MAX_RETRY_COUNT,
                )

            ReliabilityEventLogger
                .scoped(ReliabilityStage.TRANSCRIPTION, correlationId, recordingId)
                .failure("worker_exception", message = errorMessage)

            try {
                val updated =
                    recordingRepository.failTranscriptionExecution(
                        recordingId,
                        executionToken,
                        disposition.status,
                        errorMessage,
                    )
                if (!updated) {
                    logStaleTranscription(recordingId, correlationId, "transcription_error_stale")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to persist transcription error state", e)
            }

            return if (disposition.retry) {
                Result.retry()
            } else {
                // I18N-05: the notification shows classified, actionable copy; the raw
                // exception message stays in the reliability log and the persisted row.
                showTranscriptionErrorNotification(
                    recordingId,
                    transcriptionFailureNotificationText(applicationContext, exception),
                )
                buildTranscriptionFailureResult(errorMessage)
            }
        }

        private suspend fun waitForInactiveRecording(
            recordingId: UUID,
            correlationId: String,
            recordingDurationMs: Long,
        ) {
            try {
                awaitRecordingInactive(
                    recordingState = recordingStateManager.state,
                    timeoutMs = computeActiveWaitTimeoutMs(recordingDurationMs),
                )
            } catch (e: ActiveRecordingWaitTimeoutException) {
                ReliabilityEventLogger
                    .scoped(ReliabilityStage.TRANSCRIPTION, correlationId, recordingId)
                    .failure("active_recording_wait_timeout", e)
                throw e
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
