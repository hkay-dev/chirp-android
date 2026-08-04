package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.core.llm.RecordingTextEnhancementContext
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.model.EnhancementSubworkStatus
import dev.chirpboard.app.data.model.RecordingEnhancementResult
import dev.chirpboard.app.data.model.RecordingEnhancementSubworkState
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.UUID

@HiltWorker
class RecordingEnhancementWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val recordingRepository: RecordingRepository,
        private val wordReplacementRepository: WordReplacementRepository,
        private val wordReplacer: WordReplacer,
        private val textEnhancement: RecordingTextEnhancementPort,
        private val completionExporter: TranscriptionCompletionExporter,
        private val terminalNotificationDelivery: TerminalRecordingNotificationDelivery,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "RecordingEnhancement"
            const val OUTPUT_ERROR = "error"
            // This work deliberately has no network constraint so turning AI off can resolve a
            // queued row while the phone is offline. Twelve exponential attempts cover an
            // overnight loss of connectivity, then leave the saved raw transcript in a
            // terminal retryable state instead of retrying forever.
            internal const val MAX_RUN_ATTEMPTS = 12

            internal fun shouldRetry(
                exception: Throwable,
                runAttemptCount: Int,
                maxRunAttempts: Int = MAX_RUN_ATTEMPTS,
            ): Boolean = exception is IOException && runAttemptCount + 1 < maxRunAttempts
        }

        override suspend fun doWork(): Result {
            val recordingIdString =
                inputData.getString(RecordingEnhancementWorkRequest.INPUT_RECORDING_ID)
                    ?: return buildEnhancementFailureResult("Missing recording ID")
            val recordingId =
                try {
                    UUID.fromString(recordingIdString)
                } catch (e: IllegalArgumentException) {
                    return buildEnhancementFailureResult("Invalid recording ID format")
                }

            val correlationId =
                inputData.getString(RecordingEnhancementWorkRequest.INPUT_CORRELATION_ID)
                    ?: ReliabilityEventLogger.newCorrelationId("enhancement")
            val executionToken =
                inputData.getString(RecordingEnhancementWorkRequest.INPUT_EXECUTION_TOKEN)
                    ?: return buildEnhancementFailureResult("Missing enhancement execution token")

            return try {
                enhanceRecording(recordingId, correlationId, executionToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleError(recordingId, correlationId, executionToken, e)
            }
        }

        private suspend fun enhanceRecording(
            recordingId: UUID,
            correlationId: String,
            executionToken: String,
        ): Result {
            val enhancementLog =
                ReliabilityEventLogger.scoped(
                    stage = ReliabilityStage.ENHANCEMENT,
                    correlationId = correlationId,
                    recordingId = recordingId,
                )
            val snapshot = recordingRepository.beginEnhancement(recordingId, executionToken)
            if (snapshot == null) {
                val recording = recordingRepository.getRecording(recordingId)
                val transcript = recordingRepository.getTranscript(recordingId)
                if (recording == null) {
                    return buildEnhancementFailureResult("Recording not found: $recordingId")
                }
                if (transcript == null) {
                    val errorMessage = "No transcript found for enhancement"
                    val failed = recordingRepository.failEnhancement(recordingId, executionToken, errorMessage)
                    enhancementLog.failure("enhancement_missing_transcript", message = errorMessage)
                    if (failed && recording.terminalNotificationPending) {
                        terminalNotificationDelivery.deliverRequested(recordingId)
                    }
                    return buildEnhancementFailureResult(errorMessage)
                }
                enhancementLog.skipped("enhancement_ownership_lost")
                return Result.success()
            }

            val transcript = snapshot.transcript
            val execution = snapshot.execution
            val hasExecutableSubwork =
                execution.processingMode.requested ||
                    execution.title.requested ||
                    execution.summary.requested
            if (!hasExecutableSubwork && !execution.legacyRequiresResolution) {
                enhancementLog.skipped("enhancement_not_requested")
                if (recordingRepository.skipEnhancement(recordingId, executionToken)) {
                    // Skip resolves the row to terminal COMPLETED; this recording never
                    // passed the transcription worker's export site, so export here.
                    completionExporter.exportIfCompleted(recordingId)
                    if (snapshot.recording.terminalNotificationPending) {
                        terminalNotificationDelivery.deliverRequested(recordingId)
                    }
                }
                return Result.success()
            }
            if (!hasExecutableSubwork && execution.legacyRequiresResolution) {
                val errorMessage = "Legacy enhancement request requires full recovery"
                val failed = recordingRepository.failEnhancement(recordingId, executionToken, errorMessage)
                enhancementLog.failure("legacy_enhancement_requires_resolution", message = errorMessage)
                if (failed && snapshot.recording.terminalNotificationPending) {
                    terminalNotificationDelivery.deliverRequested(recordingId)
                }
                return buildEnhancementFailureResult(errorMessage)
            }
            if (!textEnhancement.isEnhancementEnabled()) {
                enhancementLog.skipped("enhancement_disabled")
                if (recordingRepository.skipEnhancement(recordingId, executionToken)) {
                    completionExporter.exportIfCompleted(recordingId)
                    if (snapshot.recording.terminalNotificationPending) {
                        terminalNotificationDelivery.deliverRequested(recordingId)
                    }
                }
                return Result.success()
            }
            if (!textEnhancement.isEnhancementAvailable(execution.llmProviderId)) {
                val errorMessage = "LLM credentials unavailable for queued enhancement"
                val failed = recordingRepository.failEnhancement(recordingId, executionToken, errorMessage)
                enhancementLog.failure("llm_unavailable", message = errorMessage)
                if (failed && snapshot.recording.terminalNotificationPending) {
                    terminalNotificationDelivery.deliverRequested(recordingId)
                }
                return buildEnhancementFailureResult(errorMessage)
            }

            // PIPE-02: enhancement is usually enqueued by TranscriptionWorker minutes after
            // the user backgrounded the app, where an unguarded setForeground throws
            // ForegroundServiceStartNotAllowedException and would park the recording in
            // FAILED until next launch. An LLM call fits the normal window, so continue
            // without foreground on that path.
            trySetWorkerForeground(buildEnhancementForegroundInfo(applicationContext), TAG)
            enhancementLog.started("enhancement_started")

            val baseProcessedText =
                transcript.processedText
                    ?: wordReplacer.apply(
                        transcript.rawText,
                        wordReplacementRepository.getEnabledReplacements(),
                    )

            var textForEnrichment = baseProcessedText
            var enrichmentContext =
                RecordingTextEnhancementContext(
                    text = textForEnrichment,
                    providerId = execution.llmProviderId,
                    modelId = execution.llmModelId,
                    recordingId = recordingId.toString(),
                )
            var transformedText: String? = null
            var transformedMode: String? = null
            var generatedTitle: String? = null
            var generatedSummary: String? = null
            var processingStatus: EnhancementSubworkStatus? = null
            var processingError: String? = null
            var titleStatus: EnhancementSubworkStatus? = null
            var titleError: String? = null
            var summaryStatus: EnhancementSubworkStatus? = null
            var summaryError: String? = null

            execution.processingModeId?.takeIf { execution.processingMode.shouldRun() }?.let { modeId ->
                val transformResult =
                    textEnhancement.processResolved(
                        context = enrichmentContext,
                        prompt = execution.processingModePrompt,
                        fallbackProcessingModeId = modeId,
                    )
                if (transformResult.isSuccess) {
                    textForEnrichment = transformResult.getOrThrow()
                    enrichmentContext =
                        RecordingTextEnhancementContext(
                            text = textForEnrichment,
                            providerId = execution.llmProviderId,
                            modelId = execution.llmModelId,
                            recordingId = recordingId.toString(),
                        )
                    transformedText = textForEnrichment
                    transformedMode = modeId
                    processingStatus = EnhancementSubworkStatus.SUCCEEDED
                } else {
                    handleRetryableSubworkFailure(
                        recordingId = recordingId,
                        correlationId = correlationId,
                        executionToken = executionToken,
                        exception = transformResult.exceptionOrNull(),
                    )?.let { return it }
                    val message = transformResult.exceptionOrNull()?.message ?: "Processing mode transform failed"
                    processingStatus = EnhancementSubworkStatus.FAILED
                    processingError = message
                    Log.w(TAG, "Skipping transcript transform", transformResult.exceptionOrNull())
                }
            }

            if (execution.title.shouldRun()) {
                val titleResult =
                    textEnhancement.generateTitle(enrichmentContext)
                if (titleResult.isSuccess) {
                    // A model that ignores instructions can return paragraphs, wrapping
                    // quotes, or markdown; the title becomes the export filename and the
                    // share subject, so normalize before persisting.
                    val sanitizedTitle = sanitizeGeneratedTitle(titleResult.getOrThrow())
                    if (sanitizedTitle.isNotBlank()) {
                        generatedTitle = sanitizedTitle
                        titleStatus = EnhancementSubworkStatus.SUCCEEDED
                    } else {
                        titleStatus = EnhancementSubworkStatus.FAILED
                        titleError = "Generated title was empty"
                        Log.w(TAG, "Skipping title generation: sanitized title was empty")
                    }
                } else {
                    handleRetryableSubworkFailure(
                        recordingId = recordingId,
                        correlationId = correlationId,
                        executionToken = executionToken,
                        exception = titleResult.exceptionOrNull(),
                    )?.let { return it }
                    val message = titleResult.exceptionOrNull()?.message ?: "Title generation failed"
                    titleStatus = EnhancementSubworkStatus.FAILED
                    titleError = message
                    Log.w(TAG, "Skipping title generation", titleResult.exceptionOrNull())
                }
            }

            if (execution.summary.shouldRun()) {
                val summaryResult =
                    textEnhancement.generateSummary(enrichmentContext)
                if (summaryResult.isSuccess) {
                    val sanitizedSummary = sanitizeGeneratedSummary(summaryResult.getOrThrow())
                    if (sanitizedSummary.isNotBlank()) {
                        generatedSummary = sanitizedSummary
                        summaryStatus = EnhancementSubworkStatus.SUCCEEDED
                    } else {
                        summaryStatus = EnhancementSubworkStatus.FAILED
                        summaryError = "Generated summary was empty"
                        Log.w(TAG, "Skipping summary generation: sanitized summary was empty")
                    }
                } else {
                    handleRetryableSubworkFailure(
                        recordingId = recordingId,
                        correlationId = correlationId,
                        executionToken = executionToken,
                        exception = summaryResult.exceptionOrNull(),
                    )?.let { return it }
                    val message = summaryResult.exceptionOrNull()?.message ?: "Summary generation failed"
                    summaryStatus = EnhancementSubworkStatus.FAILED
                    summaryError = message
                    Log.w(TAG, "Skipping summary generation", summaryResult.exceptionOrNull())
                }
            }

            val applied =
                processingStatus == EnhancementSubworkStatus.SUCCEEDED ||
                    titleStatus == EnhancementSubworkStatus.SUCCEEDED ||
                    summaryStatus == EnhancementSubworkStatus.SUCCEEDED
            if (applied) {
                enhancementLog.success("enhancement_applied")
            } else {
                enhancementLog.failure("enhancement_failed")
            }

            val committed =
                recordingRepository.completeEnhancement(
                    recordingId = recordingId,
                    executionToken = executionToken,
                    sourceTranscriptRevision = execution.sourceTranscriptRevision,
                    result =
                        RecordingEnhancementResult(
                            processedText = transformedText ?: baseProcessedText.takeIf { transcript.processedText == null },
                            processingMode = transformedMode ?: "word_replacement".takeIf { transcript.processedText == null },
                            title = generatedTitle,
                            summary = generatedSummary,
                            processingModeStatus = processingStatus,
                            processingModeError = processingError,
                            titleStatus = titleStatus,
                            titleError = titleError,
                            summaryStatus = summaryStatus,
                            summaryError = summaryError,
                        ),
                )
            if (!committed) {
                enhancementLog.skipped("enhancement_commit_stale")
            } else {
                // Export only at the terminal COMPLETED transition: the exporter re-checks
                // the row status, so an unresolved-subwork commit that landed in FAILED is
                // never exported (PLH-3/ERR-5).
                completionExporter.exportIfCompleted(recordingId)
                if (snapshot.recording.terminalNotificationPending) {
                    terminalNotificationDelivery.deliverRequested(recordingId)
                }
            }
            return Result.success()
        }

        private suspend fun handleRetryableSubworkFailure(
            recordingId: UUID,
            correlationId: String,
            executionToken: String,
            exception: Throwable?,
        ): Result? {
            if (exception is CancellationException) throw exception
            return (exception as? IOException)?.let {
                handleError(recordingId, correlationId, executionToken, it)
            }
        }

        private suspend fun handleError(
            recordingId: UUID,
            correlationId: String,
            executionToken: String,
            exception: Exception,
        ): Result {
            val errorMessage = exception.message ?: "Unknown enhancement error"
            val enhancementLog =
                ReliabilityEventLogger.scoped(
                    stage = ReliabilityStage.ENHANCEMENT,
                    correlationId = correlationId,
                    recordingId = recordingId,
                )
            if (shouldRetry(exception, runAttemptCount)) {
                val reparked =
                    try {
                        recordingRepository.reparkEnhancementExecution(
                            recordingId = recordingId,
                            executionToken = executionToken,
                            errorMessage = errorMessage,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not persist enhancement retry state", e)
                        false
                    }
                if (reparked) {
                    enhancementLog.failure("enhancement_retryable_exception", message = errorMessage)
                } else {
                    enhancementLog.skipped("enhancement_retry_stale", message = errorMessage)
                }
                return Result.retry()
            }

            val updated = recordingRepository.failEnhancement(recordingId, executionToken, errorMessage)
            if (updated) {
                enhancementLog.failure("enhancement_exception", message = errorMessage)
                val recording = recordingRepository.getRecording(recordingId)
                if (recording?.terminalNotificationPending == true) {
                    terminalNotificationDelivery.deliverRequested(recordingId)
                }
            } else {
                enhancementLog.skipped("enhancement_error_stale", message = errorMessage)
            }
            return buildEnhancementFailureResult(errorMessage)
        }
    }

private fun RecordingEnhancementSubworkState.shouldRun(): Boolean =
    requested && status in setOf(EnhancementSubworkStatus.PENDING, EnhancementSubworkStatus.FAILED)

private fun buildEnhancementFailureResult(errorMessage: String): androidx.work.ListenableWorker.Result =
    androidx.work.ListenableWorker.Result.failure(
        Data
            .Builder()
            .putString(RecordingEnhancementWorker.OUTPUT_ERROR, errorMessage)
            .build(),
    )
