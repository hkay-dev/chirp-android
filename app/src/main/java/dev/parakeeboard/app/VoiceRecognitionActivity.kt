package dev.parakeeboard.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.parakeeboard.app.llm.ProcessingMode
import dev.parakeeboard.app.llm.ProcessingModeRepository
import dev.parakeeboard.app.llm.TextProcessor
import dev.parakeeboard.app.ui.theme.ParakeetTheme
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
    private lateinit var modeRepository: ProcessingModeRepository
    private lateinit var textProcessor: TextProcessor
    private val _isProcessing = MutableStateFlow(false)
    private val _shouldDismiss = MutableStateFlow(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "VoiceRecognitionActivity created")
        
        prefs = Preferences(this)
        modeRepository = ProcessingModeRepository(this)
        textProcessor = TextProcessor()
        
        // Get singleton recognizer (already initialized by keyboard service)
        lifecycleScope.launch {
            recognizer = RecognizerManager.getRecognizer(applicationContext)
            Log.d(TAG, "Got recognizer from manager, ready: ${recognizer?.isReady}")
        }
        
        setContent {
            ParakeetTheme {
                val llmEnabled = remember { mutableStateOf(prefs.llmEnabled) }
                val currentMode by modeRepository.currentMode.collectAsState(initial = ProcessingMode.Proofread)
                
                VoiceRecognitionDialog(
                    amplitudesFlow = recorder.amplitudes,
                    isProcessingFlow = _isProcessing,
                    shouldDismissFlow = _shouldDismiss,
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

@Composable
private fun VoiceRecognitionDialog(
    amplitudesFlow: StateFlow<List<Float>>,
    isProcessingFlow: StateFlow<Boolean>,
    shouldDismissFlow: StateFlow<Boolean>,
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
    var isVisible by remember { mutableStateOf(false) }
    
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
    
    // Animated entry/exit wrapper
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + scaleIn(
            initialScale = 0.8f,
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ),
        exit = fadeOut(
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        ) + scaleOut(
            targetScale = 0.9f,
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        )
    ) {
        DialogContent(
            isRecording = isRecording,
            isProcessing = isProcessing,
            amplitudes = amplitudes,
            llmEnabled = llmEnabled,
            currentMode = currentMode,
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
    isRecording: Boolean,
    isProcessing: Boolean,
    amplitudes: List<Float>,
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onToggleLlm: (Boolean) -> Unit
) {
    // Minimal centered card (no full-screen background)
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center)
            .widthIn(max = 320.dp),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Microphone icon with animation
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                // Show circular progress indicator when processing
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(80.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                }
                // Show radial pulse animation when recording
                else if (isRecording) {
                    RadialPulse(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        amplitude = amplitudes.maxOrNull() ?: 0f
                    )
                }
                
                // Microphone icon
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
            
            // Status text
            Text(
                when {
                    isProcessing -> "Processing..."
                    isRecording -> "Listening..."
                    else -> "Ready"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // LLM Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "LLM Post-processing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = llmEnabled,
                    onCheckedChange = onToggleLlm,
                    enabled = isRecording
                )
            }
            
            // Show current mode when LLM is enabled
            if (llmEnabled) {
                Text(
                    "Mode: ${currentMode.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Stop button (prominent) and Cancel (secondary)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                TextButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel")
                }
                
                Button(
                    onClick = onStop,
                    enabled = isRecording && !isProcessing
                ) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun RadialPulse(
    color: Color,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f + (amplitude * 0.3f), // Scale based on amplitude
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Canvas(modifier = modifier.size(80.dp)) {
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = size.minDimension / 2 * scale,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
