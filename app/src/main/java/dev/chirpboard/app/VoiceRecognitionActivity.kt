package dev.chirpboard.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
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
    private val recorder by lazy { VoiceRecorder(this, lifecycleScope, inputDeviceSelector) }
    private var recordingJob: Job? = null

    @Inject lateinit var transcriberProvider: TranscriberProvider

    @Inject lateinit var prefs: Preferences

    @Inject lateinit var audioSettingsStore: AudioSettingsStore

    @Inject lateinit var inputDeviceSelector: AudioInputDeviceSelector

    @Inject lateinit var modeRepository: ProcessingModeRepository

    @Inject lateinit var textProcessor: TextProcessor

    private val transcriptionPipeline by lazy {
        VoiceRecognitionPipeline(
            tag = TAG,
            transcriberProvider = transcriberProvider,
            textProcessor = textProcessor,
        )
    }
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val _shouldDismiss = MutableStateFlow(false)
    private val _partialTranscript = MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "VoiceRecognitionActivity created")

        androidx.core.view.WindowCompat
            .setDecorFitsSystemWindows(window, false)
        val params = window.attributes
        params.gravity = android.view.Gravity.BOTTOM
        params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
        params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
        // Don't dim the background and watch for outside touches to dismiss
        params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        window.attributes = params

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
                    waveformBuffer = recorder.waveformBuffer,
                    sampleCountFlow = recorder.sampleCountFlow,
                    recordingStateFlow = _recordingState,
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

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event?.action == android.view.MotionEvent.ACTION_OUTSIDE) {
            Log.d(TAG, "Touched outside, cancelling recording")
            cancelRecording()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun startRecording() {
        if (_recordingState.value !is RecordingState.Idle) {
            Log.w(TAG, "Already recording, ignoring start request")
            return
        }
        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            Log.e(TAG, "Recording permission missing")
            returnError(SpeechRecognizer.ERROR_AUDIO)
            return
        }
        lifecycleScope.launch {
            try {
                recorder.gainMultiplier = audioSettingsStore.currentMicrophoneGain()

                if (!recorder.start()) {
                    Log.e(TAG, "Failed to start recording")
                    returnError(SpeechRecognizer.ERROR_AUDIO)
                    return@launch
                }
                _recordingState.value = RecordingState.Recording(dev.chirpboard.app.core.recording.RecordingOrigin.APP)

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
        if (_recordingState.value is RecordingState.Stopping || _recordingState.value is RecordingState.Idle) {
            Log.w(TAG, "Not recording or already stopping, ignoring stop request")
            return
        }
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Stop button pressed (LLM: $llmEnabled, Mode: ${processingMode.id})")
                _recordingState.value = RecordingState.Stopping(dev.chirpboard.app.core.recording.RecordingOrigin.APP)
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
        _recordingState.value = RecordingState.Idle
        dismissWithResult(Activity.RESULT_CANCELED)
    }

    override fun onDestroy() {
        recorder.close()
        super.onDestroy()
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
