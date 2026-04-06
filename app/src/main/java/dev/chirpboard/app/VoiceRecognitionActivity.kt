package dev.chirpboard.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.feature.keyboard.recorder.VoiceRecorder
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Minimal dialog-style activity for voice recognition (like Google's).
 * Handles android.speech.action.RECOGNIZE_SPEECH intents from other apps.
 */
@AndroidEntryPoint
class VoiceRecognitionActivity : ComponentActivity() {
    private val recorder by lazy { VoiceRecorder(this) }
    private var recordingJob: Job? = null

    @Inject lateinit var transcriberProvider: TranscriberProvider

    @Inject lateinit var prefs: Preferences

    @Inject lateinit var modeRepository: ProcessingModeRepository

    @Inject lateinit var textProcessor: TextProcessor

    private val transcriptionPipeline by lazy {
        VoiceRecognitionPipeline(
            tag = TAG,
            transcriberProvider = transcriberProvider,
            textProcessor = textProcessor,
        )
    }
    private val _isProcessing = MutableStateFlow(false)
    private val _shouldDismiss = MutableStateFlow(false)
    private val _partialTranscript = MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "VoiceRecognitionActivity created")

        androidx.core.view.WindowCompat
            .setDecorFitsSystemWindows(window, false)

        // Ensure transcriber is initialized
        lifecycleScope.launch {
            Log.d(TAG, "Initializing transcriber...")
            transcriberProvider.initialize()
            Log.d(TAG, "Transcriber ready: ${transcriberProvider.isReady()}")
        }

        setContent {
            ChirpTheme {
                val llmEnabled = remember { mutableStateOf(prefs.llmEnabled) }
                val currentMode by modeRepository.currentMode.collectAsStateWithLifecycle(initialValue = ProcessingMode.Proofread)

                VoiceRecognitionDialog(
                    amplitudesFlow = recorder.amplitudes,
                    isProcessingFlow = _isProcessing,
                    shouldDismissFlow = _shouldDismiss,
                    partialTranscriptFlow = _partialTranscript,
                    llmEnabled = llmEnabled.value,
                    currentMode = currentMode,
                    onStart = ::startRecording,
                    onStop = { stopRecording(llmEnabled.value, currentMode) },
                    onCancel = ::cancelRecording,
                    onDismissComplete = { finish() },
                    onToggleLlm = { enabled ->
                        llmEnabled.value = enabled
                        prefs.llmEnabled = enabled
                    },
                )
            }
        }
    }

    private fun startRecording() {
        lifecycleScope.launch {
            try {
                // Set microphone gain before recording
                recorder.gainMultiplier = prefs.microphoneGain

                if (!recorder.start()) {
                    Log.e(TAG, "Failed to start recording")
                    returnError(SpeechRecognizer.ERROR_AUDIO)
                    return@launch
                }

                // Collect samples in background
                recordingJob =
                    lifecycleScope.launch {
                        recorder.collectSamples()
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error starting recording", e)
                returnError(SpeechRecognizer.ERROR_AUDIO)
            }
        }
    }

    private fun stopRecording(
        llmEnabled: Boolean,
        processingMode: ProcessingMode,
    ) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Stop button pressed (LLM: $llmEnabled, Mode: ${processingMode.id})")
                _isProcessing.value = true // Start processing indicator
                recordingJob?.cancel()
                val samples = recorder.stop()
                Log.d(TAG, "Got ${samples.size} audio samples")

                when (
                    val result =
                        transcriptionPipeline.process(
                            samples = samples,
                            llmEnabled = llmEnabled,
                            processingMode = processingMode,
                            onPartialTranscript = { _partialTranscript.value = it },
                        )
                ) {
                    is VoiceRecognitionPipelineResult.Success -> {
                        Log.d(TAG, "Returning result to caller: '${result.text}'")
                        dismissWithResult(
                            resultCode = Activity.RESULT_OK,
                            data =
                                Intent().apply {
                                    putStringArrayListExtra(
                                        RecognizerIntent.EXTRA_RESULTS,
                                        arrayListOf(result.text),
                                    )
                                },
                        )
                    }

                    is VoiceRecognitionPipelineResult.Error -> {
                        returnError(result.code)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error during recognition", e)
                returnError(android.speech.SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    private fun cancelRecording() {
        recordingJob?.cancel()
        recorder.stop()
        dismissWithResult(Activity.RESULT_CANCELED)
    }

    private fun returnError(errorCode: Int) {
        Log.w(TAG, "Returning canceled result with error code: $errorCode")
        val results =
            Intent().apply {
                putExtra(RecognizerIntent.EXTRA_RESULTS, ArrayList<String>())
            }
        dismissWithResult(Activity.RESULT_CANCELED, results)
    }

    private fun dismissWithResult(
        resultCode: Int,
        data: Intent? = null,
    ) {
        setResult(resultCode, data)
        Log.d(TAG, "Triggering dismiss animation")
        _shouldDismiss.value = true
    }

    companion object {
        private const val TAG = "VoiceRecognitionActivity"
    }
}
