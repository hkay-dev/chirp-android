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
import androidx.lifecycle.lifecycleScope
import dev.chirpboard.app.llm.ProcessingMode
import dev.chirpboard.app.llm.ProcessingModeRepository
import dev.chirpboard.app.llm.TextProcessor
import dev.chirpboard.app.ui.theme.ChirpTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Minimal dialog-style activity for voice recognition (like Google's).
 * Handles android.speech.action.RECOGNIZE_SPEECH intents from other apps.
 */
class VoiceRecognitionActivity : ComponentActivity() {
    private val recorder = VoiceRecorder()
    private var recognizer: SherpaRecognizer? = null
    private var recordingJob: Job? = null
    private lateinit var prefs: Preferences
    private lateinit var securePrefs: SecurePreferences
    private lateinit var modeRepository: ProcessingModeRepository
    private lateinit var textProcessor: TextProcessor
    private val transcriptionPipeline by lazy {
        VoiceRecognitionPipeline(
            tag = TAG,
            recognizerProvider = { recognizer },
            textProcessor = textProcessor
        )
    }
    private val _isProcessing = MutableStateFlow(false)
    private val _shouldDismiss = MutableStateFlow(false)
    private val _partialTranscript = MutableStateFlow("")
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "VoiceRecognitionActivity created")
        
        prefs = Preferences(this)
        securePrefs = SecurePreferences(this)
        modeRepository = ProcessingModeRepository(this)
        
        // Get API key from secure storage, fallback to empty string if unavailable
        val apiKey = securePrefs.geminiApiKey ?: ""
        textProcessor = TextProcessor(apiKey, prefs.geminiModel)
        
        // Get singleton recognizer (already initialized by keyboard service)
        lifecycleScope.launch {
            recognizer = RecognizerManager.getRecognizer(applicationContext)
            Log.d(TAG, "Got recognizer from manager, ready: ${recognizer?.isReady}")
        }
        
        setContent {
            ChirpTheme {
                val llmEnabled = remember { mutableStateOf(prefs.llmEnabled) }
                val currentMode by modeRepository.currentMode.collectAsState(initial = ProcessingMode.Proofread)
                
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
                    }
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
                recordingJob = lifecycleScope.launch {
                    recorder.collectSamples()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                returnError(SpeechRecognizer.ERROR_AUDIO)
            }
        }
    }
    
    private fun stopRecording(llmEnabled: Boolean, processingMode: ProcessingMode) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Stop button pressed (LLM: $llmEnabled, Mode: ${processingMode.id})")
                _isProcessing.value = true  // Start processing indicator
                recordingJob?.cancel()
                val samples = recorder.stop()
                Log.d(TAG, "Got ${samples.size} audio samples")

                when (
                    val result = transcriptionPipeline.process(
                        samples = samples,
                        llmEnabled = llmEnabled,
                        processingMode = processingMode,
                        onPartialTranscript = { _partialTranscript.value = it }
                    )
                ) {
                    is VoiceRecognitionPipelineResult.Success -> {
                        Log.d(TAG, "Returning result to caller: '${result.text}'")
                        dismissWithResult(
                            resultCode = Activity.RESULT_OK,
                            data = Intent().apply {
                                putStringArrayListExtra(
                                    RecognizerIntent.EXTRA_RESULTS,
                                    arrayListOf(result.text)
                                )
                            }
                        )
                    }

                    is VoiceRecognitionPipelineResult.Error -> {
                        returnError(result.code)
                    }
                }

            } catch (e: Exception) {
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
        val results = Intent().apply {
            putExtra(RecognizerIntent.EXTRA_RESULTS, ArrayList<String>())
        }
        dismissWithResult(Activity.RESULT_CANCELED, results)
    }

    private fun dismissWithResult(resultCode: Int, data: Intent? = null) {
        setResult(resultCode, data)
        Log.d(TAG, "Triggering dismiss animation")
        _shouldDismiss.value = true
    }
    
    companion object {
        private const val TAG = "VoiceRecognitionActivity"
    }
}
