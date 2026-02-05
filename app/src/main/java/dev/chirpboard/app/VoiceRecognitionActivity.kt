package dev.chirpboard.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.lifecycleScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.components.BreathingPulse
import dev.chirpboard.app.core.ui.components.ThinkingDots
import dev.chirpboard.app.llm.ProcessingMode
import dev.chirpboard.app.llm.ProcessingModeRepository
import dev.chirpboard.app.llm.TextProcessor
import dev.chirpboard.app.ui.theme.ChirpTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
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
                
                if (samples.isEmpty()) {
                    Log.w(TAG, "No audio samples")
                    returnError(SpeechRecognizer.ERROR_NO_MATCH)
                    return@launch
                }
                
                val rec = recognizer
                if (rec == null) {
                    Log.e(TAG, "Recognizer is null")
                    returnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                    return@launch
                }
                
                Log.d(TAG, "Checking if recognizer is ready: ${rec.isReady}")
                if (!rec.isReady) {
                    Log.w(TAG, "Recognizer not ready")
                    returnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                    return@launch
                }
                
                // Transcribe
                Log.d(TAG, "Starting transcription...")
                var text = rec.transcribe(samples)
                Log.d(TAG, "Raw transcription: '$text' (length: ${text.length})")
                
                // Update partial transcript for UI preview
                _partialTranscript.value = text
                
                if (text.isBlank()) {
                    Log.w(TAG, "Empty transcription result")
                    returnError(SpeechRecognizer.ERROR_NO_MATCH)
                    return@launch
                }
                
                // Apply LLM processing if enabled
                if (llmEnabled) {
                    Log.d(TAG, "Applying LLM processing with mode: ${processingMode.id}")
                    val result = textProcessor.process(text, processingMode)
                    result.onSuccess { processedText ->
                        text = processedText
                        Log.d(TAG, "Processed text: '$text'")
                    }.onFailure { error ->
                        Log.w(TAG, "LLM processing failed: ${error.message}, using raw text")
                    }
                }
                
                // Return result with trailing space
                Log.d(TAG, "Returning result to caller: '$text'")
                val results = Intent().apply {
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS,
                        arrayListOf("$text ")  // Always add space after
                    )
                }
                setResult(Activity.RESULT_OK, results)
                Log.d(TAG, "Triggering dismiss animation")
                
                // Trigger exit animation before finishing
                _shouldDismiss.value = true
                // Activity will finish when animation completes (via onDismissComplete callback)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during recognition", e)
                returnError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }
    
    private fun cancelRecording() {
        recordingJob?.cancel()
        recorder.stop()
        setResult(Activity.RESULT_CANCELED)
        
        // Trigger exit animation before finishing
        _shouldDismiss.value = true
        // Activity will finish when animation completes (via onDismissComplete callback)
    }
    
    private fun returnError(errorCode: Int) {
        val results = Intent().apply {
            putExtra(RecognizerIntent.EXTRA_RESULTS, ArrayList<String>())
        }
        setResult(Activity.RESULT_CANCELED, results)
        
        // Trigger exit animation before finishing
        _shouldDismiss.value = true
        // Activity will finish when animation completes (via onDismissComplete callback)
    }
    
    companion object {
        private const val TAG = "VoiceRecognitionActivity"
    }
}

/** Recognition state for the dialog */
private enum class RecognitionState {
    Idle,
    Listening,
    Processing
}

@Composable
private fun VoiceRecognitionDialog(
    amplitudesFlow: StateFlow<List<Float>>,
    isProcessingFlow: StateFlow<Boolean>,
    shouldDismissFlow: StateFlow<Boolean>,
    partialTranscriptFlow: StateFlow<String>,
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onDismissComplete: () -> Unit,
    onToggleLlm: (Boolean) -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }
    val amplitudes by amplitudesFlow.collectAsState()
    val isProcessing by isProcessingFlow.collectAsState()
    val shouldDismiss by shouldDismissFlow.collectAsState()
    val partialTranscript by partialTranscriptFlow.collectAsState()
    var isVisible by remember { mutableStateOf(false) }
    
    // Derive recognition state
    val recognitionState = when {
        isProcessing -> RecognitionState.Processing
        isRecording -> RecognitionState.Listening
        else -> RecognitionState.Idle
    }
    
    LaunchedEffect(Unit) {
        // Delay slightly to trigger enter animation
        kotlinx.coroutines.delay(50)
        isVisible = true
        // Auto-start recording when dialog opens
        isRecording = true
        onStart()
    }
    
    // Watch for dismiss trigger
    LaunchedEffect(shouldDismiss) {
        if (shouldDismiss) {
            isVisible = false
            // Wait for exit animation to complete
            kotlinx.coroutines.delay(250)
            onDismissComplete()
        }
    }
    
    // Custom enter/exit transitions
    val enterTransition = fadeIn(
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + scaleIn(
        initialScale = 0.9f,
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + slideIn(
        initialOffset = { IntOffset(0, 20) },
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    )
    
    val exitTransition = fadeOut(
        animationSpec = tween(250, easing = FastOutSlowInEasing)
    ) + scaleOut(
        targetScale = 0.95f,
        animationSpec = tween(250, easing = FastOutSlowInEasing)
    ) + slideOut(
        targetOffset = { IntOffset(0, 10) },
        animationSpec = tween(250, easing = FastOutSlowInEasing)
    )
    
    // Animated entry/exit wrapper
    AnimatedVisibility(
        visible = isVisible,
        enter = enterTransition,
        exit = exitTransition
    ) {
        DialogContent(
            recognitionState = recognitionState,
            partialTranscript = partialTranscript,
            llmEnabled = llmEnabled,
            currentMode = currentMode,
            onStart = {
                isRecording = true
                onStart()
            },
            onStop = {
                isRecording = false
                onStop()
            },
            onCancel = {
                isVisible = false
                // Delay to let exit animation play
                kotlinx.coroutines.MainScope().launch {
                    kotlinx.coroutines.delay(250)
                    onCancel()
                }
            },
            onToggleLlm = onToggleLlm
        )
    }
}

@Composable
private fun DialogContent(
    recognitionState: RecognitionState,
    partialTranscript: String,
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onToggleLlm: (Boolean) -> Unit
) {
    // Animated mic container size
    val containerSize by animateDpAsState(
        targetValue = if (recognitionState == RecognitionState.Listening) 80.dp else 72.dp,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "mic_container_size"
    )
    
    // Minimal centered card
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center)
            .widthIn(max = 280.dp),
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Box {
            // X button in top-right corner
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(40.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Column(
                modifier = Modifier.padding(
                    top = 32.dp,
                    bottom = 24.dp,
                    start = 24.dp,
                    end = 24.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Microphone with pulse/dots
                Box(
                    modifier = Modifier.size(containerSize),
                    contentAlignment = Alignment.Center
                ) {
                    // Show breathing pulse when listening
                    BreathingPulse(
                        isActive = recognitionState == RecognitionState.Listening,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        baseSize = 72.dp,
                        expandedSize = containerSize + 16.dp
                    )
                    
                    // Show thinking dots when processing
                    if (recognitionState == RecognitionState.Processing) {
                        ThinkingDots(
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = 24.dp)
                        )
                    }
                    
                    // Microphone icon container
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Recording",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                // Status text - only show when Listening or Processing
                when (recognitionState) {
                    RecognitionState.Listening -> {
                        Text(
                            "Listening",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    RecognitionState.Processing -> {
                        Text(
                            "Processing",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    RecognitionState.Idle -> {
                        // No status text for Idle
                    }
                }
                
                // Live transcription preview
                if (partialTranscript.isNotBlank()) {
                    Text(
                        text = partialTranscript,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                
                // Contextual LLM Controls
                LlmControlSection(
                    llmEnabled = llmEnabled,
                    currentMode = currentMode,
                    isRecording = recognitionState == RecognitionState.Listening,
                    onToggleLlm = onToggleLlm
                )
                
                // Single transforming action button
                ActionButton(
                    recognitionState = recognitionState,
                    onStart = onStart,
                    onStop = onStop
                )
            }
        }
    }
}

@Composable
private fun LlmControlSection(
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    isRecording: Boolean,
    onToggleLlm: (Boolean) -> Unit
) {
    if (!llmEnabled) {
        // Small text to enable LLM
        Text(
            text = "Enhance with AI",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(
                enabled = isRecording,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onToggleLlm(true)
            }
        )
    } else {
        // Compact pill showing mode
        Surface(
            modifier = Modifier
                .height(16.dp)
                .clickable(
                    enabled = isRecording,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    onToggleLlm(false)
                },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI: ${currentMode.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    recognitionState: RecognitionState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    when (recognitionState) {
        RecognitionState.Idle -> {
            FilledTonalButton(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Start Listening")
            }
        }
        RecognitionState.Listening -> {
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Stop")
            }
        }
        RecognitionState.Processing -> {
            Button(
                onClick = { },
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Processing...")
            }
        }
    }
}
