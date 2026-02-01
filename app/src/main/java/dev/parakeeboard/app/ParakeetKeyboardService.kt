package dev.parakeeboard.app

import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
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
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<KeyboardState>(KeyboardState.ModelNotReady)
    private val state = _state.asStateFlow()

    private val recorder = VoiceRecorder()
    private lateinit var recognizer: SherpaRecognizer
    private lateinit var downloader: ModelDownloader
    private lateinit var textProcessor: TextProcessor
    private lateinit var prefs: Preferences
    private lateinit var db: AppDatabase
    private lateinit var modeRepository: ProcessingModeRepository

    private val _llmEnabled = MutableStateFlow(true)
    private val _currentMode = MutableStateFlow<ProcessingMode>(ProcessingMode.Raw)

    private var recordingJob: Job? = null
    
    // Custom recomposer for IME
    private val recomposerScope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private val recomposer = Recomposer(recomposerScope.coroutineContext)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Start the recomposer
        recomposerScope.launch {
            recomposer.runRecomposeAndApplyChanges()
        }

        recognizer = SherpaRecognizer(this)
        downloader = ModelDownloader(this)
        textProcessor = TextProcessor()
        prefs = Preferences(this)
        db = AppDatabase.getInstance(this)
        modeRepository = ProcessingModeRepository(this)
        _llmEnabled.value = prefs.llmEnabled

        // Observe processing mode changes
        scope.launch {
            modeRepository.currentMode.collect { mode ->
                _currentMode.value = mode
            }
        }

        initializeModel()
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

    private fun initializeModel() {
        // Already initialized or initializing
        if (recognizer.isReady || _state.value is KeyboardState.Downloading) {
            if (recognizer.isReady) _state.value = KeyboardState.Idle
            return
        }

        scope.launch {
            if (downloader.isModelDownloaded()) {
                Log.d(TAG, "Model downloaded, initializing recognizer...")
                if (recognizer.initialize()) {
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
                val currentMode by _currentMode.collectAsState(initial = ProcessingMode.Raw)
                KeyboardUI(
                    stateFlow = state,
                    amplitudesFlow = recorder.amplitudes,
                    llmEnabled = llmEnabled.value,
                    currentMode = currentMode,
                    onTap = ::onTap,
                    onToggleLlm = ::toggleLlm,
                    onModeChange = ::changeMode
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
        if (!recognizer.isReady) {
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
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        recomposer.cancel()
        recomposerScope.cancel()
        scope.cancel()
        recognizer.release()
        super.onDestroy()
    }

    private fun onTap() {
        Log.d(TAG, "onTap, state: ${_state.value}")

        when (val currentState = _state.value) {
            is KeyboardState.Idle -> startRecording()
            is KeyboardState.Recording -> stopAndTranscribe()
            is KeyboardState.Error -> {
                // Retry - re-initialize
                initializeModel()
            }
            is KeyboardState.ModelNotReady -> {
                // Start download
                startDownload()
            }
            else -> {
                // Ignore taps during transcription/download
            }
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
                val rawText = recognizer.transcribe(samples)
                Log.d(TAG, "Transcribed: $rawText")

                if (rawText.isBlank()) {
                    _state.value = KeyboardState.Idle
                    return@launch
                }

                // LLM post-processing if enabled and not Raw mode
                val mode = _currentMode.value
                if (_llmEnabled.value && mode !is ProcessingMode.Raw) {
                    _state.value = KeyboardState.Polishing
                    val result = textProcessor.process(rawText, mode)
                    
                    result.fold(
                        onSuccess = { polishedText ->
                            Log.d(TAG, "Polished: $polishedText")
                            currentInputConnection?.commitText(polishedText, 1)
                            saveTranscription(rawText, polishedText)
                            _state.value = KeyboardState.Idle
                        },
                        onFailure = { error ->
                            Log.e(TAG, "LLM failed, using raw text", error)
                            currentInputConnection?.commitText(rawText, 1)
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
                    currentInputConnection?.commitText(rawText, 1)
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
                        if (recognizer.initialize()) {
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
