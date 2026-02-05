package dev.chirpboard.app.feature.keyboard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.telephony.TelephonyManager
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
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import dev.chirpboard.app.feature.keyboard.haptic.HapticFeedback
import dev.chirpboard.app.feature.keyboard.recorder.AudioEncoder
import dev.chirpboard.app.feature.keyboard.recorder.AudioFocusManager
import dev.chirpboard.app.feature.keyboard.recorder.RecordingError
import dev.chirpboard.app.feature.keyboard.recorder.VoiceRecorder
import java.io.File
import java.util.UUID
import dev.chirpboard.app.feature.keyboard.state.KeyboardState
import dev.chirpboard.app.feature.keyboard.state.toKeyboardState
import dev.chirpboard.app.core.recording.RecordingState
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
    @Inject lateinit var recordingRepository: RecordingRepository

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<KeyboardState>(KeyboardState.ModelNotReady)
    private val state = _state.asStateFlow()

    private val recorder = VoiceRecorder()
    private val audioEncoder = AudioEncoder()
    private lateinit var audioFocusManager: AudioFocusManager
    private var phoneCallHandler: PhoneCallHandler? = null
    
    /** Stores samples from last recording for potential persistence */
    private var lastRecordingSamples: FloatArray? = null

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

        // Initialize audio focus manager
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusManager = AudioFocusManager(audioManager)
        audioFocusManager.onFocusLost = {
            Log.w(TAG, "Audio focus lost during recording")
            if (_state.value is KeyboardState.Recording) {
                // Stop recording gracefully
                stopAndTranscribe()
            }
        }

        // Initialize phone call handler to stop recording during calls
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        telephonyManager?.let { tm ->
            phoneCallHandler = PhoneCallHandler(tm, mainExecutor)
            phoneCallHandler?.onCallStateChanged = { isInCall ->
                if (isInCall && _state.value is KeyboardState.Recording) {
                    Log.w(TAG, "Phone call detected, stopping recording")
                    stopAndTranscribe()
                }
            }
            phoneCallHandler?.register()
        }

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

        // Sync keyboard state from RecordingStateManager for recording-related states.
        // This ensures keyboard state reflects the global recording state (e.g., if another
        // source stops recording, or if an error occurs in RecordingStateManager).
        // Keyboard-specific states (Transcribing, Polishing, ModelNotReady, LlmError, Downloading)
        // are still managed directly by this service.
        scope.launch {
            recordingStateManager.state.collect { recordingState ->
                val currentKeyboardState = _state.value
                
                // Only sync if we're in a recording-related state or the recording state changed
                // Don't override keyboard-specific states like Transcribing, Polishing, etc.
                val isKeyboardSpecificState = currentKeyboardState is KeyboardState.Transcribing ||
                    currentKeyboardState is KeyboardState.Polishing ||
                    currentKeyboardState is KeyboardState.Downloading ||
                    currentKeyboardState is KeyboardState.ModelNotReady ||
                    currentKeyboardState is KeyboardState.LlmError
                
                if (!isKeyboardSpecificState) {
                    val derivedState = recordingState.toKeyboardState()
                    // Only update if different to avoid unnecessary recompositions
                    if (derivedState != currentKeyboardState) {
                        Log.d(TAG, "Syncing state from RecordingStateManager: $recordingState -> $derivedState")
                        _state.value = derivedState
                    }
                }
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
            audioFocusManager.abandonFocus()
            recordingJob?.cancel()
            recorder.stop()
            recordingStateManager.onRecordingCompleted()
            _state.value = KeyboardState.Idle
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - Stopping foreground service")
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        // Unregister phone call handler
        phoneCallHandler?.unregister()
        phoneCallHandler = null
        
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

        // Request audio focus first
        when (audioFocusManager.requestFocus()) {
            is AudioFocusManager.FocusResult.Denied -> {
                Log.e(TAG, "Audio focus denied - another app is using audio")
                _state.value = KeyboardState.Error("Another app is using audio")
                return
            }
            is AudioFocusManager.FocusResult.Granted -> {
                Log.d(TAG, "Audio focus granted, starting recording")
            }
            else -> { /* shouldn't happen */ }
        }

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
                audioFocusManager.abandonFocus()
                return
            }
        }

        // Set microphone gain before recording
        recorder.gainMultiplier = _microphoneGain.value
        
        // Set error callback to handle recording errors
        recorder.onRecordingError = { error ->
            Log.e(TAG, "Recording error: ${error.userMessage}")
            audioFocusManager.abandonFocus()
            recordingStateManager.onRecordingError(error.userMessage)
            _state.value = KeyboardState.Error(error.userMessage)
        }

        if (!recorder.start()) {
            _state.value = KeyboardState.Error("Failed to start recording")
            recordingStateManager.onRecordingError("Failed to start recording")
            audioFocusManager.abandonFocus()
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

        // Abandon audio focus since we're done recording
        audioFocusManager.abandonFocus()

        HapticFeedback.onRecordStop(this)
        recordingJob?.cancel()
        val samples = recorder.stop()
        
        // Store samples for potential persistence
        lastRecordingSamples = samples

        if (samples.isEmpty()) {
            lastRecordingSamples = null
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
     * When saveKeyboardRecordings is ON, saves the recording to database.
     */
    private suspend fun handleTranscriptionComplete(rawText: String, processedText: String?) {
        val shouldSave = keyboardPreferences.saveKeyboardRecordings.first()
        val samples = lastRecordingSamples
        
        if (shouldSave && samples != null && samples.isNotEmpty()) {
            saveKeyboardRecording(rawText, processedText, samples)
        } else {
            lastRecordingSamples = null  // Free memory
        }
    }
    
    /**
     * Saves a keyboard recording to persistent storage.
     * Encodes audio to M4A and creates Recording + Transcript entities.
     */
    private fun saveKeyboardRecording(rawText: String, processedText: String?, samples: FloatArray) {
        scope.launch(Dispatchers.IO) {
            try {
                val filename = "keyboard_${System.currentTimeMillis()}.m4a"
                val recordingsDir = File(filesDir, "recordings")
                recordingsDir.mkdirs()
                val outputPath = File(recordingsDir, filename).absolutePath
                
                val success = audioEncoder.encodeToM4a(samples, VoiceRecorder.SAMPLE_RATE, outputPath)
                if (!success) {
                    Log.e(TAG, "Failed to encode keyboard recording")
                    return@launch
                }
                
                val durationMs = (samples.size * 1000L) / VoiceRecorder.SAMPLE_RATE
                
                // Create recording
                val recording = Recording(
                    id = UUID.randomUUID(),
                    title = rawText.take(50).ifEmpty { "Keyboard recording" },
                    audioPath = outputPath,
                    status = RecordingStatus.COMPLETED,
                    source = RecordingSource.KEYBOARD,
                    profileId = null,
                    durationMs = durationMs
                )
                
                // Create transcript
                val transcript = Transcript(
                    id = UUID.randomUUID(),
                    recordingId = recording.id,
                    rawText = rawText,
                    processedText = processedText
                )
                
                // Save atomically
                recordingRepository.createRecordingWithTranscript(recording, transcript)
                
                Log.i(TAG, "Saved keyboard recording: ${recording.id}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save keyboard recording", e)
            } finally {
                lastRecordingSamples = null  // Free memory
            }
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
