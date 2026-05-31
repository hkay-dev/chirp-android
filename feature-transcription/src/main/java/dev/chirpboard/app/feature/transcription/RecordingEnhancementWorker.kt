package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.model.RecordingEnhancementResult
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
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
        private val textProcessor: TextProcessor,
        private val modeRepository: ProcessingModeRepository,
        private val llmClient: LlmClient,
        private val llmPreferences: dev.chirpboard.app.feature.llm.settings.LlmPreferences,
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

            return try {
                enhanceRecording(recordingId, correlationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleError(recordingId, correlationId, e)
            }
        }

        private suspend fun enhanceRecording(
            recordingId: UUID,
            correlationId: String,
        ): Result {
            val snapshot = recordingRepository.beginEnhancement(recordingId)
            if (snapshot == null) {
                val recording = recordingRepository.getRecording(recordingId)
                val transcript = recordingRepository.getTranscript(recordingId)
                if (recording == null) {
                    return buildEnhancementFailureResult("Recording not found: $recordingId")
                }
                if (transcript == null) {
                    val errorMessage = "No transcript found for enhancement"
                    recordingRepository.failEnhancement(recordingId, errorMessage)
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
                logSkipped(recordingId, correlationId, "enhancement_intent_missing")
                recordingRepository.skipEnhancement(recordingId)
                return Result.success()
            }

            val transcript = snapshot.transcript
            val intent = snapshot.intent
            if (!intent.hasRequestedWork) {
                logSkipped(recordingId, correlationId, "enhancement_not_requested")
                recordingRepository.skipEnhancement(recordingId)
                return Result.success()
            }
            if (!llmPreferences.getLlmEnabled() || llmPreferences.fetchApiKey().isNullOrBlank()) {
                logSkipped(recordingId, correlationId, "llm_unavailable")
                recordingRepository.skipEnhancement(recordingId)
                return Result.success()
            }

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
            var transformApplied = false
            var titleApplied = false
            var summaryApplied = false
            var transformedText: String? = null
            var transformedMode: String? = null
            var generatedTitle: String? = null
            var generatedSummary: String? = null

            intent.processingModeId?.let { modeId ->
                val mode = modeRepository.resolveMode(modeId)
                val transformResult = textProcessor.process(baseProcessedText, mode)
                if (transformResult.isSuccess) {
                    textForEnrichment = transformResult.getOrThrow()
                    transformedText = textForEnrichment
                    transformedMode = mode.id
                    transformApplied = true
                } else {
                    Log.w(TAG, "Skipping transcript transform", transformResult.exceptionOrNull())
                }
            }

            if (intent.autoTitle) {
                val titleResult = llmClient.generateTitle(textForEnrichment)
                if (titleResult.isSuccess) {
                    generatedTitle = titleResult.getOrThrow()
                    titleApplied = true
                } else {
                    Log.w(TAG, "Skipping title generation", titleResult.exceptionOrNull())
                }
            }

            if (intent.autoSummary) {
                val summaryResult = llmClient.generateSummary(textForEnrichment)
                if (summaryResult.isSuccess) {
                    generatedSummary = summaryResult.getOrThrow()
                    summaryApplied = true
                } else {
                    Log.w(TAG, "Skipping summary generation", summaryResult.exceptionOrNull())
                }
            }

            val applied = transformApplied || titleApplied || summaryApplied
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.ENHANCEMENT,
                outcome = if (applied) ReliabilityOutcome.SUCCESS else ReliabilityOutcome.FAILURE,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = if (applied) "enhancement_applied" else "enhancement_failed",
            )
            recordingRepository.completeEnhancement(
                recordingId,
                RecordingEnhancementResult(
                    processedText = transformedText ?: baseProcessedText.takeIf { transcript.processedText == null },
                    processingMode = transformedMode ?: "word_replacement".takeIf { transcript.processedText == null },
                    title = generatedTitle,
                    summary = generatedSummary,
                ),
            )
            return Result.success()
        }

        private suspend fun handleError(
            recordingId: UUID,
            correlationId: String,
            exception: Exception,
        ): Result {
            val errorMessage = exception.message ?: "Unknown enhancement error"
            recordingRepository.failEnhancement(recordingId, errorMessage)
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.ENHANCEMENT,
                outcome = ReliabilityOutcome.FAILURE,
                correlationId = correlationId,
                recordingId = recordingId,
                reasonCode = "enhancement_exception",
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

private fun buildEnhancementFailureResult(errorMessage: String): androidx.work.ListenableWorker.Result =
    androidx.work.ListenableWorker.Result.failure(
        Data
            .Builder()
            .putString(RecordingEnhancementWorker.OUTPUT_ERROR, errorMessage)
            .build(),
    )
