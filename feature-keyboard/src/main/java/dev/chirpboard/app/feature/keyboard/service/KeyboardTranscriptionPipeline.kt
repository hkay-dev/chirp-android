package dev.chirpboard.app.feature.keyboard.service

import android.util.Log
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import dev.chirpboard.app.feature.keyboard.recorder.AudioEncoder
import dev.chirpboard.app.feature.keyboard.state.KeyboardState
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal class KeyboardTranscriptionPipeline(
    private val tag: String,
    private val recognizerProvider: TranscriberProvider,
    private val textProcessor: TextProcessor,
    private val keyboardPreferences: KeyboardPreferences,
    private val obsidianManager: dev.chirpboard.app.feature.obsidian.ObsidianManager,
    private val obsidianPreferences: dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences,
    private val persistenceScope: CoroutineScope,
    private val filesDirProvider: () -> File,
    private val audioEncoder: AudioEncoder,
    private val recordingRepository: RecordingRepository,
    private val getBufferedSamples: () -> FloatArray?,
    private val setBufferedSamples: (FloatArray?) -> Unit,
    private val getPersistenceJob: () -> Job?,
    private val setPersistenceJob: (Job?) -> Unit,
) {
    suspend fun run(
        samples: FloatArray,
        currentMode: ProcessingMode,
        llmEnabled: Boolean,
        commitText: (String) -> Unit,
        onStateChanged: (KeyboardState) -> Unit,
        onRecordingCompleted: () -> Unit,
        onRecordingError: (String) -> Unit,
    ) {
        var rawTextForPersistence: String? = null

        try {
            val correlationId = ReliabilityEventLogger.newCorrelationId("keyboard")
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.TRANSCRIPTION,
                outcome = ReliabilityOutcome.STARTED,
                correlationId = correlationId,
                reasonCode = "keyboard_transcription_started",
            )

            if (!recognizerProvider.isReady()) {
                Log.e(tag, "Recognizer not ready for transcription")
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = correlationId,
                    reasonCode = "keyboard_recognizer_not_ready",
                )
                withContext(Dispatchers.Main) {
                    persistBufferedKeyboardCapture(
                        rawText = null,
                        processedText = null,
                        errorMessage = "Recognizer not ready",
                    )
                    onStateChanged(KeyboardState.Error("Recognizer not ready"))
                    onRecordingError("Recognizer not ready")
                }
                return
            }

            val transcriptionOutcome = recognizerProvider.transcribe(samples)
            val mappedOutcome = mapKeyboardTranscriptionOutcome(transcriptionOutcome)
            val rawText =
                when (mappedOutcome) {
                    is KeyboardTranscriptionResolution.Success -> {
                        mappedOutcome.text
                    }

                    KeyboardTranscriptionResolution.NoSpeech -> {
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.TRANSCRIPTION,
                            outcome = ReliabilityOutcome.SKIPPED,
                            correlationId = correlationId,
                            reasonCode = "keyboard_no_speech",
                        )
                        withContext(Dispatchers.Main) {
                            discardBufferedKeyboardCapture()
                            onRecordingCompleted()
                            onStateChanged(KeyboardState.Idle)
                        }
                        return
                    }

                    is KeyboardTranscriptionResolution.Failure -> {
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.TRANSCRIPTION,
                            outcome = ReliabilityOutcome.FAILURE,
                            correlationId = correlationId,
                            reasonCode = "keyboard_transcription_failed",
                            message = mappedOutcome.message,
                        )
                        withContext(Dispatchers.Main) {
                            persistBufferedKeyboardCapture(
                                rawText = rawTextForPersistence,
                                processedText = null,
                                errorMessage = mappedOutcome.message,
                            )
                            onStateChanged(KeyboardState.Error(mappedOutcome.message))
                            onRecordingError(mappedOutcome.message)
                        }
                        return
                    }
                }

            Log.d(tag, "Transcribed: $rawText")
            rawTextForPersistence = rawText
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.TRANSCRIPTION,
                outcome = ReliabilityOutcome.SUCCESS,
                correlationId = correlationId,
                reasonCode = "keyboard_transcription_completed",
            )

            withContext(Dispatchers.Main) {
                onRecordingCompleted()
            }

            if (llmEnabled) {
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.ENHANCEMENT,
                    outcome = ReliabilityOutcome.STARTED,
                    correlationId = correlationId,
                    reasonCode = "keyboard_enhancement_started",
                )
                withContext(Dispatchers.Main) {
                    onStateChanged(KeyboardState.Polishing)
                }

                val result =
                    withTimeoutOrNull(10_000L) {
                        textProcessor.process(rawText, currentMode)
                    }

                withContext(Dispatchers.Main) {
                    if (result != null) {
                        result.fold(
                            onSuccess = { polishedText ->
                                Log.d(tag, "Polished: $polishedText")
                                ReliabilityEventLogger.log(
                                    stage = ReliabilityStage.ENHANCEMENT,
                                    outcome = ReliabilityOutcome.SUCCESS,
                                    correlationId = correlationId,
                                    reasonCode = "keyboard_enhancement_completed",
                                )
                                commitText("$polishedText ")
                                handleTranscriptionComplete(rawText, polishedText)
                                onStateChanged(KeyboardState.Idle)
                            },
                            onFailure = { error ->
                                Log.e(tag, "LLM failed, using raw text", error)
                                ReliabilityEventLogger.log(
                                    stage = ReliabilityStage.ENHANCEMENT,
                                    outcome = ReliabilityOutcome.FAILURE,
                                    correlationId = correlationId,
                                    reasonCode = "keyboard_enhancement_failed",
                                    message = error.message,
                                )
                                commitText("$rawText ")
                                handleTranscriptionComplete(rawText, null)
                                onStateChanged(KeyboardState.LlmError("LLM failed: ${error.message}"))
                            },
                        )
                    } else {
                        Log.w(tag, "LLM timed out after 10s, using raw text")
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.ENHANCEMENT,
                            outcome = ReliabilityOutcome.FAILURE,
                            correlationId = correlationId,
                            reasonCode = "keyboard_enhancement_timeout",
                        )
                        commitText("$rawText ")
                        handleTranscriptionComplete(rawText, null)
                        onStateChanged(KeyboardState.Idle)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    commitText("$rawText ")
                    handleTranscriptionComplete(rawText, null)
                    onStateChanged(KeyboardState.Idle)
                }
            }
        } catch (e: CancellationException) {
            Log.w(tag, "Transcription cancelled", e)
            withContext(NonCancellable + Dispatchers.Main) {
                persistBufferedKeyboardCapture(
                    rawText = rawTextForPersistence,
                    processedText = null,
                    errorMessage = "Keyboard closed during transcription",
                )
            }
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Transcription failed", e)
            val errorMessage = "Transcription failed: ${e.message}"
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.TRANSCRIPTION,
                outcome = ReliabilityOutcome.FAILURE,
                correlationId = ReliabilityEventLogger.newCorrelationId("keyboard"),
                reasonCode = "keyboard_exception",
                message = e.message,
            )
            withContext(NonCancellable + Dispatchers.Main) {
                persistBufferedKeyboardCapture(
                    rawText = rawTextForPersistence,
                    processedText = null,
                    errorMessage = errorMessage,
                )
                onStateChanged(KeyboardState.Error(errorMessage))
                onRecordingError(errorMessage)
            }
        }
    }

    suspend fun persistBufferedKeyboardCapture(
        rawText: String?,
        processedText: String?,
        errorMessage: String? = null,
    ) {
        val shouldSave = keyboardPreferences.saveKeyboardRecordings.first()
        val samples = getBufferedSamples()

        if (!shouldSave || samples == null || samples.isEmpty()) {
            setBufferedSamples(null)
            return
        }

        val sampleSnapshot = samples.copyOf()
        setBufferedSamples(null)
        val persistencePlan =
            buildKeyboardPersistencePlan(
                rawText = rawText,
                processedText = processedText,
                errorMessage = errorMessage,
            )

        val job =
            persistenceScope.launch {
                val recording = saveKeyboardRecording(
                    filesDir = filesDirProvider(),
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    persistencePlan = persistencePlan,
                    samples = sampleSnapshot,
                )
                if (recording != null && rawText != null) {
                    val autoExport = obsidianPreferences.autoExportEnabled.first()
                    val vaultUriStr = obsidianPreferences.globalVaultUri.first()
                    if (autoExport && vaultUriStr != null) {
                        try {
                            val uri = android.net.Uri.parse(vaultUriStr)
                            obsidianManager.export(
                                recording = recording,
                                transcript = processedText ?: rawText,
                                summary = null,
                                vaultUri = uri
                            )
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to auto-export to Obsidian", e)
                        }
                    }
                }
            }
        setPersistenceJob(job)

        try {
            job.join()
        } finally {
            if (getPersistenceJob() === job) {
                setPersistenceJob(null)
            }
        }
    }

    fun discardBufferedKeyboardCapture() {
        setBufferedSamples(null)
    }

    private suspend fun handleTranscriptionComplete(
        rawText: String,
        processedText: String?,
    ) {
        persistBufferedKeyboardCapture(
            rawText = rawText,
            processedText = processedText,
        )
    }
}
