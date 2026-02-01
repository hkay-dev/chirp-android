package dev.parakeeboard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.parakeeboard.app.db.AppDatabase
import dev.parakeeboard.app.db.Transcription
import dev.parakeeboard.app.download.ModelDownloader
import dev.parakeeboard.app.llm.ProcessingMode
import dev.parakeeboard.app.llm.ProcessingModeRepository
import dev.parakeeboard.app.llm.TextProcessor
import dev.parakeeboard.app.ui.KeyboardUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ParakeetKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
    companion object {
        private const val TAG = "ParakeetKeyboard"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "parakeeboard_service"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<KeyboardState>(KeyboardState.ModelNotReady)
    private val state = _state.asStateFlow()

    private val recorder = VoiceRecorder()
    private var recognizer: SherpaRecognizer? = null
    private lateinit var downloader: ModelDownloader
    private lateinit var textProcessor: TextProcessor
    private lateinit var prefs: Preferences
    private lateinit var db: AppDatabase
    private lateinit var modeRepository: ProcessingModeRepository

    private val _llmEnabled = MutableStateFlow(true)
    private val _currentMode = MutableStateFlow<ProcessingMode>(ProcessingMode.Proofread)

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

        downloader = ModelDownloader(this)
        prefs = Preferences(this)
        textProcessor = TextProcessor(prefs.geminiApiKey, prefs.geminiModel)
        db = AppDatabase.getInstance(this)
        modeRepository = ProcessingModeRepository(this)
        _llmEnabled.value = prefs.llmEnabled

        // Observe processing mode changes
        scope.launch {
            modeRepository.currentMode.collect { mode ->
                _currentMode.value = mode
            }
        }

        // Get singleton recognizer and keep it loaded
        scope.launch {
            Log.d(TAG, "Loading recognizer singleton...")
            recognizer = RecognizerManager.getRecognizer(applicationContext)
            Log.d(TAG, "Recognizer loaded and ready: ${recognizer?.isReady}")
        }

        initializeModel()
    }
    
    private fun startForegroundService() {
        createNotificationChannel()
        
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parakeeboard")
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
                "Parakeeboard Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps voice recognition model loaded in memory"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun saveTranscription(rawText: String, processedText: String?) {
        scope.launch(Dispatchers.IO) {
            db.transcriptionDao().insert(Transcription(rawText = rawText, processedText = processedText))
        }
    }

    private fun toggleLlm() {
        val newValue = !_llmEnabled.value
        _llmEnabled.value = newValue
        prefs.llmEnabled = newValue
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
        // Already initialized or initializing
        val rec = recognizer
        if (rec?.isReady == true || _state.value is KeyboardState.Downloading) {
            if (rec?.isReady == true) _state.value = KeyboardState.Idle
            return
        }

        scope.launch {
            if (downloader.isModelDownloaded()) {
                Log.d(TAG, "Model downloaded, initializing recognizer...")
                val currentRec = recognizer
                if (currentRec != null && currentRec.initialize()) {
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
            setViewTreeLifecycleOwner(this@ParakeetKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@ParakeetKeyboardService)
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
        if (recognizer?.isReady != true) {
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
        
        // Don't release the recognizer - keep it in singleton for next time
        // recognizer?.release()
        
        super.onDestroy()
    }

    private fun onTap() {
        val currentState = _state.value
        when (currentState) {
            is KeyboardState.Idle -> {
                // Set microphone gain before recording
                recorder.gainMultiplier = prefs.microphoneGain
                startRecording()
            }
            is KeyboardState.Recording -> stopAndTranscribe()
            is KeyboardState.ModelNotReady -> initializeModel()
            is KeyboardState.Error -> initializeModel()
            is KeyboardState.LlmError -> _state.value = KeyboardState.Idle
            else -> {}
        }
    }

    private fun startRecording() {
        Log.d(TAG, "Starting recording")

        if (!recorder.start()) {
            _state.value = KeyboardState.Error("Failed to start recording")
            return
        }

        HapticFeedback.onRecordStart(this)
        _state.value = KeyboardState.Recording

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
            _state.value = KeyboardState.Idle
            return
        }

        _state.value = KeyboardState.Transcribing

        scope.launch {
            try {
                val rec = recognizer
                if (rec == null || !rec.isReady) {
                    Log.e(TAG, "Recognizer not ready for transcription")
                    _state.value = KeyboardState.Error("Recognizer not ready")
                    return@launch
                }
                
                val rawText = rec.transcribe(samples)
                Log.d(TAG, "Transcribed: $rawText")

                if (rawText.isBlank()) {
                    _state.value = KeyboardState.Idle
                    return@launch
                }

                // LLM post-processing if enabled and not Raw mode
                val mode = _currentMode.value
                if (_llmEnabled.value) {
                    _state.value = KeyboardState.Polishing
                    val result = textProcessor.process(rawText, mode)
                    
                    result.fold(
                        onSuccess = { polishedText ->
                            Log.d(TAG, "Polished: $polishedText")
                            // Always add space after transcript
                            currentInputConnection?.commitText("$polishedText ", 1)
                            saveTranscription(rawText, polishedText)
                            _state.value = KeyboardState.Idle
                        },
                        onFailure = { error ->
                            Log.e(TAG, "LLM failed, using raw text", error)
                            // Always add space after transcript
                            currentInputConnection?.commitText("$rawText ", 1)
                            saveTranscription(rawText, null)
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
                    saveTranscription(rawText, null)
                    _state.value = KeyboardState.Idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                _state.value = KeyboardState.Error("Transcription failed: ${e.message}")
            }
        }
    }

    private fun startDownload() {
        scope.launch {
            downloader.downloadModel().collect { downloadState ->
                when (downloadState) {
                    is ModelDownloader.DownloadState.Progress -> {
                        val progress = downloadState.bytesDownloaded.toFloat() / downloadState.totalBytes
                        _state.value = KeyboardState.Downloading(progress)
                    }
                    is ModelDownloader.DownloadState.Complete -> {
                        Log.i(TAG, "Download complete, initializing model")
                        val rec = recognizer
                        if (rec != null && rec.initialize()) {
                            _state.value = KeyboardState.Idle
                        } else {
                            _state.value = KeyboardState.Error("Failed to load model")
                        }
                    }
                    is ModelDownloader.DownloadState.Error -> {
                        _state.value = KeyboardState.Error(downloadState.message)
                    }
                }
            }
        }
    }
}
