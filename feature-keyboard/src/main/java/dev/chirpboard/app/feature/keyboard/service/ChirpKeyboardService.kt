package dev.chirpboard.app.feature.keyboard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import dev.chirpboard.app.feature.keyboard.haptic.HapticFeedback
import dev.chirpboard.app.feature.keyboard.recorder.VoiceRecorder
import dev.chirpboard.app.feature.keyboard.state.KeyboardState
import dev.chirpboard.app.feature.keyboard.ui.KeyboardUI
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Input Method Service for voice-to-text keyboard functionality.
 * Integrates with RecordingStateManager to coordinate with app and widget recording.
 */
@AndroidEntryPoint
class ChirpKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
    companion object {
        private const val TAG = "ChirpKeyboard"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "chirp_service"
        
        // Intent action for opening main activity - will be resolved at runtime
        private const val MAIN_ACTIVITY_CLASS = "dev.chirpboard.app.MainActivity"
    }

    @Inject lateinit var recordingStateManager: RecordingStateManager
    @Inject lateinit var textProcessor: TextProcessor
    @Inject lateinit var keyboardPreferences: KeyboardPreferences
    @Inject lateinit var modeRepository: ProcessingModeRepository
    @Inject lateinit var recognizerProvider: RecognizerProvider

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<KeyboardState>(KeyboardState.ModelNotReady)
    private val state = _state.asStateFlow()

    private val recorder = VoiceRecorder()

    private val _llmEnabled = MutableStateFlow(true)
    private val _currentMode = MutableStateFlow<ProcessingMode>(ProcessingMode.Proofread)
    private val _microphoneGain = MutableStateFlow(1.0f)

    private var recordingJob: Job? = null
    
    // Custom recomposer for IME
    private val recomposerScope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private val recomposer = Recomposer(recomposerScope.coroutineContext)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate - Starting foreground service to keep model in memory")
        
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Start foreground service to prevent Android from killing us
        startForegroundService()

        // Start the recomposer
        recomposerScope.launch {
            recomposer.runRecomposeAndApplyChanges()
        }

        // Observe preferences
        scope.launch {
            keyboardPreferences.llmEnabled.collect { enabled ->
                _llmEnabled.value = enabled
            }
        }
        
        scope.launch {
            keyboardPreferences.microphoneGain.collect { gain ->
                _microphoneGain.value = gain
            }
        }

        // Observe processing mode changes
        scope.launch {
            modeRepository.currentMode.collect { mode ->
                _currentMode.value = mode
            }
        }

        // Initialize recognizer
        initializeModel()
    }
    
    private fun startForegroundService() {
        createNotificationChannel()
        
        val notificationIntent = try {
            Intent().setClassName(this, MAIN_ACTIVITY_CLASS)
        } catch (e: Exception) {
            Intent()
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chirp")
            .setContentText("Voice model loaded in memory")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chirp Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps voice recognition model loaded in memory"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun toggleLlm() {
        val newValue = !_llmEnabled.value
        _llmEnabled.value = newValue
        scope.launch {
            keyboardPreferences.setLlmEnabled(newValue)
        }
    }

    private fun changeMode(mode: ProcessingMode) {
        scope.launch {
            modeRepository.setMode(mode)
        }
    }

    private fun onBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun onSpace() {
        currentInputConnection?.commitText(" ", 1)
    }

    private fun onMoveCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        
        // Get current cursor position
        val extractedText = ic.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest(),
            0
        ) ?: return
        
        val currentPos = extractedText.selectionStart
        val textLength = extractedText.text.length
        
        // Calculate new position (clamped to text bounds)
        val newPos = (currentPos + delta).coerceIn(0, textLength)
        
        // Set selection to new position
        ic.setSelection(newPos, newPos)
    }

    private fun initializeModel() {
        if (recognizerProvider.isReady() || _state.value is KeyboardState.Downloading) {
            if (recognizerProvider.isReady()) _state.value = KeyboardState.Idle
            return
        }

        scope.launch {
            if (recognizerProvider.isModelDownloaded()) {
                Log.d(TAG, "Model downloaded, initializing recognizer...")
                if (recognizerProvider.initialize()) {
                    Log.d(TAG, "Recognizer ready!")
                    _state.value = KeyboardState.Idle
                } else {
                    Log.e(TAG, "Failed to initialize recognizer")
                    _state.value = KeyboardState.Error("Failed to load model")
                }
            } else {
                Log.d(TAG, "Model not downloaded yet")
                _state.value = KeyboardState.ModelNotReady
            }
        }
    }

    override fun onCreateInputView(): View {
        return ComposeView(this).apply {
            // Set our custom recomposer so Compose doesn't look for ViewTreeLifecycleOwner
            compositionContext = recomposer
            setViewTreeLifecycleOwner(this@ChirpKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@ChirpKeyboardService)
            setContent {
                val llmEnabled = _llmEnabled.collectAsState()
                val currentMode by _currentMode.collectAsState(initial = ProcessingMode.Proofread)
                KeyboardUI(
                    stateFlow = state,
                    amplitudesFlow = recorder.amplitudes,
                    llmEnabled = llmEnabled.value,
                    currentMode = currentMode,
                    onTap = ::onTap,
                    onToggleLlm = ::toggleLlm,
                    onModeChange = ::changeMode,
                    onBackspace = ::onBackspace,
                    onSpace = ::onSpace,
                    onMoveCursor = ::onMoveCursor
                )
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView, current state: ${_state.value}")

        // Check mic permission
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = KeyboardState.Error("Microphone permission required")
            return
        }

        // Always try to initialize if not ready
        if (!recognizerProvider.isReady()) {
            initializeModel()
        } else if (_state.value is KeyboardState.ModelNotReady) {
            _state.value = KeyboardState.Idle
        }

        // Re-check model state
        if (_state.value is KeyboardState.ModelNotReady) {
            initializeModel()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView")

        // Stop recording if active
        if (_state.value is KeyboardState.Recording) {
            recordingJob?.cancel()
            recorder.stop()
            recordingStateManager.onRecordingCompleted()
            _state.value = KeyboardState.Idle
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - Stopping foreground service")
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        recomposer.cancel()
        recomposerScope.cancel()
        scope.cancel()
        
        super.onDestroy()
    }

    private fun onTap() {
        val currentState = _state.value
        when (currentState) {
            is KeyboardState.Idle -> startRecording()
            is KeyboardState.Recording -> stopAndTranscribe()
            is KeyboardState.ModelNotReady -> initializeModel()
            is KeyboardState.Error -> initializeModel()
            is KeyboardState.LlmError -> _state.value = KeyboardState.Idle
            else -> {}
        }
    }

    private fun startRecording() {
        Log.d(TAG, "Starting recording")

        // Check if another source is recording
        val result = recordingStateManager.tryStartRecording(RecordingOrigin.KEYBOARD)
        when (result) {
            is RecordingStartResult.Success -> {
                // Proceed with recording
            }
            is RecordingStartResult.AlreadyRecording -> {
                val sourceMessage = when (result.currentOrigin) {
                    RecordingOrigin.APP -> "app"
                    RecordingOrigin.WIDGET -> "widget"
                    RecordingOrigin.KEYBOARD -> "keyboard"
                }
                Toast.makeText(this, "Microphone in use by $sourceMessage", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Set microphone gain before recording
        recorder.gainMultiplier = _microphoneGain.value

        if (!recorder.start()) {
            _state.value = KeyboardState.Error("Failed to start recording")
            recordingStateManager.onRecordingError("Failed to start recording")
            return
        }

        HapticFeedback.onRecordStart(this)
        _state.value = KeyboardState.Recording
        recordingStateManager.onRecordingStarted("keyboard_temp_recording")

        recordingJob = scope.launch {
            recorder.collectSamples()
        }
    }

    private fun stopAndTranscribe() {
        Log.d(TAG, "Stopping recording and transcribing")

        HapticFeedback.onRecordStop(this)
        recordingJob?.cancel()
        val samples = recorder.stop()

        if (samples.isEmpty()) {
            recordingStateManager.onRecordingCompleted()
            _state.value = KeyboardState.Idle
            return
        }

        _state.value = KeyboardState.Transcribing
        recordingStateManager.beginStopRecording()

        scope.launch {
            try {
                if (!recognizerProvider.isReady()) {
                    Log.e(TAG, "Recognizer not ready for transcription")
                    _state.value = KeyboardState.Error("Recognizer not ready")
                    recordingStateManager.onRecordingError("Recognizer not ready")
                    return@launch
                }
                
                val rawText = recognizerProvider.transcribe(samples)
                Log.d(TAG, "Transcribed: $rawText")
                
                // Mark recording as complete since transcription succeeded
                recordingStateManager.onRecordingCompleted()

                if (rawText.isBlank()) {
                    _state.value = KeyboardState.Idle
                    return@launch
                }

                // LLM post-processing if enabled
                val mode = _currentMode.value
                if (_llmEnabled.value) {
                    _state.value = KeyboardState.Polishing
                    val result = textProcessor.process(rawText, mode)
                    
                    result.fold(
                        onSuccess = { polishedText ->
                            Log.d(TAG, "Polished: $polishedText")
                            // Always add space after transcript
                            currentInputConnection?.commitText("$polishedText ", 1)
                            handleTranscriptionComplete(rawText, polishedText)
                            _state.value = KeyboardState.Idle
                        },
                        onFailure = { error ->
                            Log.e(TAG, "LLM failed, using raw text", error)
                            // Always add space after transcript
                            currentInputConnection?.commitText("$rawText ", 1)
                            handleTranscriptionComplete(rawText, null)
                            _state.value = KeyboardState.LlmError("LLM failed: ${error.message}")
                            // Auto-clear error after 3 seconds
                            delay(3000)
                            if (_state.value is KeyboardState.LlmError) {
                                _state.value = KeyboardState.Idle
                            }
                        }
                    )
                } else {
                    // Always add space after transcript
                    currentInputConnection?.commitText("$rawText ", 1)
                    handleTranscriptionComplete(rawText, null)
                    _state.value = KeyboardState.Idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                _state.value = KeyboardState.Error("Transcription failed: ${e.message}")
                recordingStateManager.onRecordingError("Transcription failed: ${e.message}")
            }
        }
    }
    
    /**
     * Handle transcription completion.
     * When saveKeyboardRecordings is ON, this would save the recording.
     * Currently just logs - full implementation would save M4A and create Recording entity.
     */
    private suspend fun handleTranscriptionComplete(rawText: String, processedText: String?) {
        val shouldSave = keyboardPreferences.saveKeyboardRecordings.first()
        if (shouldSave) {
            // TODO: Implement saving M4A file and creating Recording entity with SOURCE = KEYBOARD
            // This would involve:
            // 1. Convert PCM samples to M4A file
            // 2. Create Recording entity in database
            // 3. Use RecordingSource.KEYBOARD as the source
            Log.d(TAG, "Would save keyboard recording: raw='$rawText', processed='$processedText'")
        }
    }
}

/**
 * Interface for the speech recognizer.
 * This allows the keyboard module to be decoupled from the specific recognizer implementation.
 */
interface RecognizerProvider {
    fun isReady(): Boolean
    fun isModelDownloaded(): Boolean
    suspend fun initialize(): Boolean
    suspend fun transcribe(samples: FloatArray): String
}
