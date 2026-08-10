package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.core.llm.GOOGLE_CLOUD_VERTEX_PROVIDER_ID
import dev.chirpboard.app.core.llm.LlmRuntimeSnapshot
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.core.transcription.CloudFileTranscriptionProvider
import dev.chirpboard.app.core.transcription.CloudFileTranscriptionRequest
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.ContinuousAudioTranscriberPreference
import dev.chirpboard.app.core.transcription.PcmFloatFileTranscriberProvider
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.model.RecordingEnhancementIntent
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.transcription.audio.AudioDecoder
import dev.chirpboard.app.feature.transcription.audio.ChunkedAudioProcessor
import dev.chirpboard.app.feature.transcription.audio.JoinedChunkTranscription
import dev.chirpboard.app.feature.transcription.audio.asSampleFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
        private val cloudTranscriber: CloudFileTranscriptionProvider,
        private val transcriptionRoutingStore: TranscriptionRoutingStore,
        private val audioDecoder: AudioDecoder,
        private val audioEncoder: AudioEncoder,
        private val recordingStateManager: dev.chirpboard.app.core.recording.RecordingStateManager,
        private val workScheduler: TranscriptionWorkScheduler,
        private val completionExporter: TranscriptionCompletionExporter,
        private val terminalNotificationDelivery: TerminalRecordingNotificationDelivery,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "TranscriptionWorker"
            const val INPUT_RECORDING_ID = "recording_id"
            const val OUTPUT_TRANSCRIPT_ID = "transcript_id"
            const val OUTPUT_ERROR = "error"
            private const val RAW_PCM_EXTENSION = "f32pcm"
            private const val CONTINUOUS_HEAP_RESERVE_BYTES = 64L * 1024L * 1024L
            private const val MAX_CONTINUOUS_GGUF_AUDIO_MS = 5L * 60L * 1_000L
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

            if (recording.transcriptionEngineId == null) {
                val selectedEngine = transcriptionRoutingStore.getSelectedEngine()
                recordingRepository.stampTranscriptionEngineIfUnset(recordingId, selectedEngine.id)
                    ?: return buildTranscriptionFailureResult("Recording not found: $recordingId")
            }

            val ownedRecording =
                recordingRepository.beginTranscriptionExecution(recordingId, executionToken)
                    ?: run {
                        logStaleTranscription(recordingId, correlationId, "transcription_ownership_lost")
                        return androidx.work.ListenableWorker.Result.success()
                    }
            val requestedTranscriptionEngine =
                TranscriptionEngine.fromId(ownedRecording.transcriptionEngineId)
                    ?: throw NonRetryableTranscriptionException("Unknown transcription engine")
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
                val updated =
                    recordingRepository.failTranscriptionExecution(
                        recordingId,
                        executionToken,
                        RecordingStatus.FAILED,
                        "Audio file not found: ${ownedRecording.audioPath}",
                    )
                transcriptionLog.failure("audio_missing")
                if (updated) {
                    notifyTerminalFailure(
                        recordingId = recordingId,
                        requested = ownedRecording.terminalNotificationPending,
                        errorText = applicationContext.getString(R.string.transcription_error_audio_missing),
                    )
                }
                return buildTranscriptionFailureResult("Audio file not found")
            }

            val cloudLocalFallbackReason =
                if (requestedTranscriptionEngine == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3) {
                    resolveCloudLocalFallbackReason(
                        durationMs = ownedRecording.durationMs,
                        audioBytes = audioFile.length(),
                        configurationStatus = { cloudTranscriber.configurationStatus() },
                    )
                } else {
                    null
                }
            val transcriptionEngine =
                if (cloudLocalFallbackReason != null) {
                    val rerouted =
                        recordingRepository.rerouteTranscriptionEngineForExecution(
                            recordingId = recordingId,
                            executionToken = executionToken,
                            expectedEngineId = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id,
                            newEngineId = TranscriptionEngine.LOCAL_PARAKEET.id,
                        )
                    if (!rerouted) {
                        logStaleTranscription(recordingId, correlationId, "cloud_limit_fallback_stale")
                        return androidx.work.ListenableWorker.Result.success()
                    }
                    transcriptionLog.recovered(cloudLocalFallbackReason)
                    TranscriptionEngine.LOCAL_PARAKEET
                } else {
                    requestedTranscriptionEngine
                }

            // Local model readiness must never gate the cloud path.
            if (transcriptionEngine == TranscriptionEngine.LOCAL_PARAKEET && !transcriberProvider.isModelDownloaded()) {
                val updated =
                    recordingRepository.failTranscriptionExecution(
                        recordingId,
                        executionToken,
                        RecordingStatus.FAILED,
                        "Model not downloaded. Please download the speech recognition model in Settings.",
                    )
                transcriptionLog.failure("model_not_downloaded")
                if (updated) {
                    notifyTerminalFailure(
                        recordingId = recordingId,
                        requested = ownedRecording.terminalNotificationPending,
                        errorText = applicationContext.getString(R.string.transcription_error_model_missing),
                    )
                }
                return buildTranscriptionFailureResult("Model not downloaded")
            }

            // Initialize the transcriber if needed
            if (transcriptionEngine == TranscriptionEngine.LOCAL_PARAKEET && !transcriberProvider.isReady()) {
                Log.d(TAG, "Initializing transcriber...")
                val initialized = transcriberProvider.initialize()
                if (!initialized) {
                    transcriptionLog.failure("model_init_failed")
                    if (transcriberProvider.isModelDownloaded()) {
                        throw RetryableTranscriptionException(
                            "Failed to initialize speech recognition model",
                        )
                    }
                    val updated =
                        recordingRepository.failTranscriptionExecution(
                            recordingId,
                            executionToken,
                            RecordingStatus.FAILED,
                            "Failed to initialize speech recognition model",
                        )
                    if (updated) {
                        notifyTerminalFailure(
                            recordingId = recordingId,
                            requested = ownedRecording.terminalNotificationPending,
                            errorText = applicationContext.getString(R.string.transcription_error_model_init),
                        )
                    }
                    return buildTranscriptionFailureResult("Failed to initialize model")
                }
            }

            // Decode and transcribe using chunked processing for memory efficiency
            // This uses 30-second chunks with 2-second overlap to prevent word truncation
            // Peak memory: ~4MB instead of ~76MB for a 10-minute recording
            Log.d(TAG, "Starting ${transcriptionEngine.id} transcription")

            val detailedTranscription: JoinedChunkTranscription
            try {
                detailedTranscription =
                    when (transcriptionEngine) {
                        TranscriptionEngine.LOCAL_PARAKEET ->
                            transcribeLocally(
                                recordingId = recordingId,
                                correlationId = correlationId,
                                audioPath = ownedRecording.audioPath,
                                durationMs = ownedRecording.durationMs,
                            )
                        TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3 ->
                            transcribeWithGoogleCloud(
                                recordingId = recordingId,
                                executionToken = executionToken,
                                audioPath = ownedRecording.audioPath,
                                durationMs = ownedRecording.durationMs,
                            ) ?: run {
                                logStaleTranscription(recordingId, correlationId, "cloud_audio_path_swap_stale")
                                return androidx.work.ListenableWorker.Result.success()
                            }
                    }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory during transcription", e)
                val updated =
                    recordingRepository.failTranscriptionExecution(
                        recordingId,
                        executionToken,
                        RecordingStatus.FAILED,
                        "Out of memory during transcription. Recording may be too long.",
                    )
                if (updated) {
                    notifyTerminalFailure(
                        recordingId = recordingId,
                        requested = ownedRecording.terminalNotificationPending,
                        errorText = applicationContext.getString(R.string.transcription_error_out_of_memory),
                    )
                }
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
            Log.d(TAG, "Transcription result received (${rawTranscriptionText.length} chars)")

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
            val (committed, enhancementQueued) =
                withSerializedQueueScheduling {
                    val resultCommitted =
                        recordingRepository.commitTranscriptionResult(
                            transcript = transcript,
                            timings = timings,
                            enhancementIntent = enhancementIntent,
                            expectedExecutionToken = executionToken,
                            enhancementExecutionToken = enhancementExecutionToken,
                        )
                    val resultQueued =
                        resultCommitted &&
                            enhancementIntent != null &&
                            enhancementExecutionToken != null &&
                            enqueueEnhancement(recordingId, enhancementExecutionToken, correlationId)
                    resultCommitted to resultQueued
                }
            if (!committed) {
                logStaleTranscription(recordingId, correlationId, "transcription_commit_stale")
                return androidx.work.ListenableWorker.Result.success()
            }
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
                if (ownedRecording.terminalNotificationPending) {
                    terminalNotificationDelivery.deliverRequested(recordingId)
                }
            }

            return buildTranscriptionSuccessResult(transcript.id)
        }

        private suspend fun transcribeLocally(
            recordingId: UUID,
            correlationId: String,
            audioPath: String,
            durationMs: Long,
        ): JoinedChunkTranscription {
            checkMemoryPressure()
            if (
                (transcriberProvider as? ContinuousAudioTranscriberPreference)?.prefersContinuousAudio() == true &&
                canBufferContinuousAudio(audioPath, durationMs)
            ) {
                try {
                    return transcribeContinuous(
                        recordingId = recordingId,
                        correlationId = correlationId,
                        audioPath = audioPath,
                        durationMs = durationMs,
                    )
                } catch (error: ContinuousAudioCapacityException) {
                    Log.w(TAG, "Continuous decode buffer estimate was short; using lossless chunk recovery", error)
                } catch (error: OutOfMemoryError) {
                    Log.w(TAG, "Continuous decode could not reserve PCM; using lossless chunk recovery", error)
                }
            }
            return transcribeLocallyChunked(recordingId, correlationId, audioPath, durationMs)
        }

        private suspend fun transcribeContinuous(
            recordingId: UUID,
            correlationId: String,
            audioPath: String,
            durationMs: Long,
        ): JoinedChunkTranscription {
            if (recordingStateManager.state.value.isActive) {
                waitForInactiveRecording(recordingId, correlationId, durationMs)
            }
            if (!transcriberProvider.isReady() && !transcriberProvider.initialize()) {
                throw RetryableTranscriptionException("Failed to re-initialize speech recognition model")
            }
            val audioFile = File(audioPath)
            val fileOutcome =
                if (audioFile.extension.equals(RAW_PCM_EXTENSION, ignoreCase = true)) {
                    (transcriberProvider as? PcmFloatFileTranscriberProvider)?.transcribePcmFloatFile(
                        path = audioPath,
                        sampleCount = audioFile.length() / Float.SIZE_BYTES,
                        sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                    )
                } else {
                    null
                }
            val chunk =
                mapOutcomeForChunkTranscription(
                    if (fileOutcome is TranscriptionOutcome.Success || fileOutcome is TranscriptionOutcome.NoSpeech) {
                        fileOutcome
                    } else {
                        transcriberProvider.transcribe(
                            collectContinuousSamples(audioPath, durationMs),
                            AudioDecoder.TARGET_SAMPLE_RATE,
                        )
                    },
                )
            return JoinedChunkTranscription(chunk.text, chunk.wordTimings)
        }

        private suspend fun transcribeLocallyChunked(
            recordingId: UUID,
            correlationId: String,
            audioPath: String,
            durationMs: Long,
        ): JoinedChunkTranscription {
            val processor =
                ChunkedAudioProcessor(
                    chunkDurationMs = 30_000,
                    overlapDurationMs = 2_000,
                    sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                )
            val audioFlow = localAudioFlow(audioPath)
            return processor.processAndJoinDetailed(audioFlow) { samples ->
                if (recordingStateManager.state.value.isActive) {
                    waitForInactiveRecording(recordingId, correlationId, durationMs)
                }
                if (!transcriberProvider.isReady() && !transcriberProvider.initialize()) {
                    throw RetryableTranscriptionException("Failed to re-initialize speech recognition model")
                }
                mapOutcomeForChunkTranscription(
                    transcriberProvider.transcribe(samples, AudioDecoder.TARGET_SAMPLE_RATE),
                )
            }
        }

        private suspend fun collectContinuousSamples(
            audioPath: String,
            durationMs: Long,
        ): FloatArray {
            val audioFile = File(audioPath)
            val expectedSamples =
                if (audioFile.extension.equals(RAW_PCM_EXTENSION, ignoreCase = true)) {
                    (audioFile.length() / Float.SIZE_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else {
                    (durationMs * AudioDecoder.TARGET_SAMPLE_RATE / 1_000L)
                        .coerceIn(AudioDecoder.TARGET_SAMPLE_RATE.toLong(), Int.MAX_VALUE.toLong())
                        .toInt()
                }
            val buffer = FloatArray(expectedSamples)
            var size = 0
            localAudioFlow(audioPath).collect { samples ->
                if (size + samples.size > buffer.size) {
                    throw ContinuousAudioCapacityException(buffer.size, size + samples.size)
                }
                System.arraycopy(samples, 0, buffer, size, samples.size)
                size += samples.size
            }
            return when {
                size == buffer.size -> buffer
                buffer.size - size <= AudioDecoder.TARGET_SAMPLE_RATE -> buffer
                else -> buffer.copyOf(size)
            }
        }

        private fun canBufferContinuousAudio(
            audioPath: String,
            durationMs: Long,
        ): Boolean {
            val audioFile = File(audioPath)
            val estimatedDurationMs =
                if (audioFile.extension.equals(RAW_PCM_EXTENSION, ignoreCase = true)) {
                    audioFile.length() / Float.SIZE_BYTES * 1_000L / AudioDecoder.TARGET_SAMPLE_RATE
                } else {
                    durationMs
                }
            if (estimatedDurationMs <= 0L || estimatedDurationMs > MAX_CONTINUOUS_GGUF_AUDIO_MS) {
                Log.i(
                    TAG,
                    "Using lossless chunk recovery for ${estimatedDurationMs}ms recording; " +
                        "continuous GGUF limit is ${MAX_CONTINUOUS_GGUF_AUDIO_MS}ms",
                )
                return false
            }
            if (
                audioFile.extension.equals(RAW_PCM_EXTENSION, ignoreCase = true) &&
                transcriberProvider is PcmFloatFileTranscriberProvider
            ) {
                return true
            }
            val expectedBytes =
                if (audioFile.extension.equals(RAW_PCM_EXTENSION, ignoreCase = true)) {
                    audioFile.length()
                } else {
                    durationMs * AudioDecoder.TARGET_SAMPLE_RATE * Float.SIZE_BYTES / 1_000L
                }
            val runtime = Runtime.getRuntime()
            val availableHeap = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            return expectedBytes > 0L && expectedBytes + CONTINUOUS_HEAP_RESERVE_BYTES <= availableHeap
        }

        private fun localAudioFlow(audioPath: String): Flow<FloatArray> {
            val audioFile = File(audioPath)
            return if (audioFile.extension.equals(RAW_PCM_EXTENSION, ignoreCase = true)) {
                if (audioFile.length() % Float.SIZE_BYTES != 0L) {
                    throw NonRetryableTranscriptionException("Raw keyboard audio is truncated")
                }
                InlineAudioSource
                    .PcmFloatFile(
                        path = audioPath,
                        sampleCount = audioFile.length() / Float.SIZE_BYTES,
                        sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                    ).asSampleFlow()
            } else {
                audioDecoder.decodeAsFlow(audioPath)
            }
        }

        private suspend fun transcribeWithGoogleCloud(
            recordingId: UUID,
            executionToken: String,
            audioPath: String,
            durationMs: Long,
        ): JoinedChunkTranscription? {
            val sourceFile = File(audioPath)
            val uploadAudio = prepareCloudUploadAudio(sourceFile, recordingId, executionToken) ?: return null
            val outcome =
                cloudTranscriber.transcribeFile(
                    CloudFileTranscriptionRequest(
                        recordingId = recordingId,
                        executionToken = executionToken,
                        audioPath = uploadAudio.file.absolutePath,
                        mimeType = uploadAudio.mimeType,
                        durationMs = durationMs,
                    ),
                )
            val mapped = mapOutcomeForChunkTranscription(outcome)
            return JoinedChunkTranscription(mapped.text, mapped.wordTimings)
        }

        private suspend fun prepareCloudUploadAudio(
            sourceFile: File,
            recordingId: UUID,
            executionToken: String,
        ): CloudUploadAudio? {
            if (!sourceFile.extension.equals(RAW_PCM_EXTENSION, ignoreCase = true)) {
                return CloudUploadAudio(
                    file = sourceFile,
                    mimeType = cloudMimeType(sourceFile),
                )
            }
            if (sourceFile.length() % Float.SIZE_BYTES != 0L) {
                throw NonRetryableTranscriptionException("Raw keyboard audio is truncated")
            }
            val safeToken = executionToken.filter { it.isLetterOrDigit() }.take(64)
            val durableWavFile =
                File(
                    sourceFile.parentFile,
                    "${sourceFile.nameWithoutExtension}-$recordingId-${safeToken.ifBlank { "run" }}.wav",
                )
            val encoded =
                withContext(Dispatchers.IO) {
                    runCatching { durableWavFile.delete() }
                    audioEncoder.encodePcmFloatFile(
                        inputPath = sourceFile.absolutePath,
                        sampleCount = sourceFile.length() / Float.SIZE_BYTES,
                        sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                        outputPath = durableWavFile.absolutePath,
                        format = RecordingOutputFormat.WAV,
                    )
                }
            if (!encoded || !WavFileWriter.hasAccurateHeader(durableWavFile)) {
                withContext(Dispatchers.IO) {
                    runCatching { durableWavFile.delete() }
                }
                throw NonRetryableTranscriptionException("Could not prepare keyboard audio for cloud transcription")
            }
            val swapped =
                recordingRepository.swapAudioPathForTranscriptionExecution(
                    recordingId = recordingId,
                    executionToken = executionToken,
                    expectedAudioPath = sourceFile.absolutePath,
                    newAudioPath = durableWavFile.absolutePath,
                )
            if (!swapped) {
                withContext(Dispatchers.IO) {
                    runCatching { durableWavFile.delete() }
                }
                return null
            }
            withContext(Dispatchers.IO) {
                runCatching { sourceFile.delete() }
            }
            return CloudUploadAudio(durableWavFile, "audio/wav")
        }

        private fun cloudMimeType(file: File): String =
            when (file.extension.lowercase()) {
                "m4a", "mp4" -> "audio/mp4"
                "wav" -> "audio/wav"
                "mp3" -> "audio/mpeg"
                else -> throw NonRetryableTranscriptionException("Unsupported cloud transcription audio format")
            }

        private suspend fun resolveEnhancementIntent(
            recordingId: UUID,
            recording: dev.chirpboard.app.data.entity.Recording,
            processedText: String,
            correlationId: String,
        ): RecordingEnhancementIntent? {
            val policy =
                if (recording.enhancementRequestSnapshotted) {
                    RecordingEnhancementPolicy(
                        processingModeId = recording.requestedProcessingModeId,
                        autoTitle = false,
                        autoSummary = false,
                    )
                } else {
                    val profile = recording.profileId?.let { profileRepository.getProfile(it) }
                    resolveRecordingEnhancementPolicy(
                        profile = profile,
                        globalAutoTitle = textEnhancement.defaultAutoTitleEnabled(),
                        globalAutoSummary = textEnhancement.defaultAutoSummaryEnabled(),
                    )
                }
            if (!policy.hasRequestedWork) {
                ReliabilityEventLogger
                    .scoped(ReliabilityStage.ENHANCEMENT, correlationId, recordingId)
                    .skipped("enhancement_not_requested")
                return null
            }
            if (!textEnhancement.isEnhancementEnabled()) {
                ReliabilityEventLogger
                    .scoped(ReliabilityStage.ENHANCEMENT, correlationId, recordingId)
                    .skipped("enhancement_disabled")
                return null
            }

            val runtimeSnapshot =
                when {
                    recording.requestedLlmProviderId != null || recording.requestedLlmModelId != null ->
                        LlmRuntimeSnapshot(
                            providerId = recording.requestedLlmProviderId,
                            modelId = recording.requestedLlmModelId,
                        )
                    recording.transcriptionEngineId == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id ->
                        LlmRuntimeSnapshot(
                            providerId = GOOGLE_CLOUD_VERTEX_PROVIDER_ID,
                            modelId = null,
                        )
                    else -> textEnhancement.runtimeSnapshot()
                }
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
         * Test seam: delegates to the top-level [showTranscriptionErrorNotification] in
         * TranscriptionWorkerSupport.kt so worker tests can stub the notification post via
         * the existing mockkStatic harness. Pure delegation — no behavior change.
         */
        private fun showTranscriptionErrorNotification(recordingId: UUID, errorMessage: String) {
            showTranscriptionErrorNotification(applicationContext, recordingId, errorMessage)
        }

        private suspend fun notifyTerminalFailure(
            recordingId: UUID,
            requested: Boolean,
            errorText: String,
        ) {
            if (requested) {
                terminalNotificationDelivery.deliverRequested(recordingId)
            } else {
                showTranscriptionErrorNotification(recordingId, errorText)
            }
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

            val updated =
                try {
                    recordingRepository.failTranscriptionExecution(
                        recordingId,
                        executionToken,
                        disposition.status,
                        errorMessage,
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to persist transcription error state", e)
                    false
                }
            if (!updated) {
                logStaleTranscription(recordingId, correlationId, "transcription_error_stale")
            }

            return if (disposition.retry) {
                Result.retry()
            } else {
                if (updated) {
                    val notificationRequested =
                        recordingRepository.getRecording(recordingId)?.terminalNotificationPending == true
                    notifyTerminalFailure(
                        recordingId = recordingId,
                        requested = notificationRequested,
                        errorText = transcriptionFailureNotificationText(applicationContext, exception),
                    )
                }
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

        private data class CloudUploadAudio(
            val file: File,
            val mimeType: String,
        )

    }

private class ContinuousAudioCapacityException(
    expectedSamples: Int,
    requiredSamples: Int,
) : Exception("Continuous PCM estimate was $expectedSamples samples but needed $requiredSamples")
