package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.model.EnhancementSubworkStatus
import dev.chirpboard.app.data.model.RecordingEnhancementResult
import dev.chirpboard.app.data.model.RecordingEnhancementSubworkState
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import kotlinx.coroutines.CancellationException
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
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "RecordingEnhancement"
            const val OUTPUT_ERROR = "error"
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
            val snapshot = recordingRepository.beginEnhancement(recordingId, executionToken)
            if (snapshot == null) {
                val recording = recordingRepository.getRecording(recordingId)
                val transcript = recordingRepository.getTranscript(recordingId)
                if (recording == null) {
                    return buildEnhancementFailureResult("Recording not found: $recordingId")
                }
                if (transcript == null) {
                    val errorMessage = "No transcript found for enhancement"
                    recordingRepository.failEnhancement(recordingId, executionToken, errorMessage)
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.ENHANCEMENT,
                        outcome = ReliabilityOutcome.FAILURE,
                        correlationId = correlationId,
                        recordingId = recordingId,
                        reasonCode = "enhancement_missing_transcript",
                        message = errorMessage,
                    )
                    return buildEnhancementFailureResult(errorMessage)
                }
                logSkipped(recordingId, correlationId, "enhancement_ownership_lost")
                return Result.success()
            }

            val transcript = snapshot.transcript
            val execution = snapshot.execution
            val hasExecutableSubwork =
                execution.processingMode.requested ||
                    execution.title.requested ||
                    execution.summary.requested
            if (!hasExecutableSubwork && !execution.legacyRequiresResolution) {
                logSkipped(recordingId, correlationId, "enhancement_not_requested")
                recordingRepository.skipEnhancement(recordingId, executionToken)
                return Result.success()
            }
            if (!hasExecutableSubwork && execution.legacyRequiresResolution) {
                val errorMessage = "Legacy enhancement request requires full recovery"
                recordingRepository.failEnhancement(recordingId, executionToken, errorMessage)
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.ENHANCEMENT,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = correlationId,
                    recordingId = recordingId,
                    reasonCode = "legacy_enhancement_requires_resolution",
                    message = errorMessage,
                )
                return buildEnhancementFailureResult(errorMessage)
            }
            if (!textEnhancement.isEnhancementAvailable(execution.llmProviderId)) {
                val errorMessage = "LLM credentials unavailable for queued enhancement"
                recordingRepository.failEnhancement(recordingId, executionToken, errorMessage)
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.ENHANCEMENT,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = correlationId,
                    recordingId = recordingId,
                    reasonCode = "llm_unavailable",
                    message = errorMessage,
                )
                return buildEnhancementFailureResult(errorMessage)
            }

            setForeground(buildEnhancementForegroundInfo(applicationContext))
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.ENHANCEMENT,
                outcome = ReliabilityOutcome.STARTED,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = "enhancement_started",
            )

            val baseProcessedText =
                transcript.processedText
                    ?: wordReplacer.apply(
                        transcript.rawText,
                        wordReplacementRepository.getEnabledReplacements(),
                    )

            var textForEnrichment = baseProcessedText
            var enrichmentContext =
                textEnhancement.createContext(
                    text = textForEnrichment,
                    providerId = execution.llmProviderId,
                    modelId = execution.llmModelId,
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
                        textEnhancement.createContext(
                            text = textForEnrichment,
                            providerId = execution.llmProviderId,
                            modelId = execution.llmModelId,
                        )
                    transformedText = textForEnrichment
                    transformedMode = modeId
                    processingStatus = EnhancementSubworkStatus.SUCCEEDED
                } else {
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
                    generatedTitle = titleResult.getOrThrow()
                    titleStatus = EnhancementSubworkStatus.SUCCEEDED
                } else {
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
                    generatedSummary = summaryResult.getOrThrow()
                    summaryStatus = EnhancementSubworkStatus.SUCCEEDED
                } else {
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
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.ENHANCEMENT,
                outcome = if (applied) ReliabilityOutcome.SUCCESS else ReliabilityOutcome.FAILURE,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = if (applied) "enhancement_applied" else "enhancement_failed",
            )

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
                logSkipped(recordingId, correlationId, "enhancement_commit_stale")
            }
            return Result.success()
        }

        private suspend fun handleError(
            recordingId: UUID,
            correlationId: String,
            executionToken: String,
            exception: Exception,
        ): Result {
            val errorMessage = exception.message ?: "Unknown enhancement error"
            val updated = recordingRepository.failEnhancement(recordingId, executionToken, errorMessage)
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.ENHANCEMENT,
                outcome = if (updated) ReliabilityOutcome.FAILURE else ReliabilityOutcome.SKIPPED,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = if (updated) "enhancement_exception" else "enhancement_error_stale",
                message = errorMessage,
            )
            return buildEnhancementFailureResult(errorMessage)
        }

        private fun logSkipped(
            recordingId: UUID,
            correlationId: String,
            reasonCode: String,
        ) {
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.ENHANCEMENT,
                outcome = ReliabilityOutcome.SKIPPED,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = reasonCode,
            )
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
