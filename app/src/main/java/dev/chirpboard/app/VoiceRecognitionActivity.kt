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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.transcription.InlineTranscriptionCoordinator
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
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

    @Inject lateinit var inlineTranscription: InlineTranscriptionCoordinator

    @Inject lateinit var audioSettingsStore: AudioSettingsStore

    @Inject lateinit var inputDeviceSelector: AudioInputDeviceSelector

    @Inject lateinit var modeRepository: ProcessingModeRepository

    @Inject lateinit var llmPreferences: LlmPreferences
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
                val llmEnabled by llmPreferences.llmEnabled.collectAsStateWithLifecycle(initialValue = true)
                val currentMode by modeRepository.currentMode.collectAsStateWithLifecycle(initialValue = ProcessingMode.Proofread)

                VoiceRecognitionDialog(
                    waveformBuffer = recorder.waveformBuffer,
                    sampleCountFlow = recorder.sampleCountFlow,
                    recordingStateFlow = _recordingState,
                    shouldDismissFlow = _shouldDismiss,
                    partialTranscriptFlow = _partialTranscript,
                    llmEnabled = llmEnabled,
                    currentMode = currentMode,
                    onStart = ::startRecording,
                    onStop = { stopRecording(llmEnabled, currentMode) },
                    onCancel = ::cancelRecording,
                    onDismissComplete = { finish() },
                    onToggleLlm = { enabled ->
                        lifecycleScope.launch {
                            llmPreferences.setLlmEnabled(enabled)
                        }
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

                if (samples.isEmpty()) {
                    returnError(SpeechRecognizer.ERROR_NO_MATCH)
                    return@launch
                }

                var resultText = ""
                inlineTranscription.transcribe(
                    request =
                        InlineTranscriptionRequest(
                            samples = samples,
                            llmEnabled = llmEnabled,
                            processingModeId = processingMode.id,
                            correlationPrefix = "voice",
                        ),
                    persistence = null,
                    commitText = { text ->
                        resultText = text
                        _partialTranscript.value = text.trim()
                    },
                    onRecordingCompleted = {
                        _recordingState.value = RecordingState.Idle
                    },
                    onRecordingError = { message ->
                        Log.e(TAG, message)
                    },
                )

                when (inlineTranscription.phase.value) {
                    is InlineTranscriptionPhase.Error,
                    is InlineTranscriptionPhase.LlmError,
                    -> returnError(SpeechRecognizer.ERROR_CLIENT)

                    InlineTranscriptionPhase.Idle -> {
                        if (resultText.isBlank()) {
                            returnError(SpeechRecognizer.ERROR_NO_MATCH)
                        } else {
                            Log.d(TAG, "Returning result to caller: '$resultText'")
                            dismissWithResult(
                                resultCode = Activity.RESULT_OK,
                                data =
                                    Intent().apply {
                                        putStringArrayListExtra(
                                            RecognizerIntent.EXTRA_RESULTS,
                                            arrayListOf(resultText),
                                        )
                                    },
                            )
                        }
                    }

                    else -> returnError(SpeechRecognizer.ERROR_CLIENT)
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
