package dev.chirpboard.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.components.BreathingPulse
import dev.chirpboard.app.core.ui.components.ThinkingDots
import dev.chirpboard.app.llm.ProcessingMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class RecognitionState {
    Idle,
    Listening,
    Processing
}

@Composable
fun VoiceRecognitionDialog(
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

    val recognitionState = when {
        isProcessing -> RecognitionState.Processing
        isRecording -> RecognitionState.Listening
        else -> RecognitionState.Idle
    }

    LaunchedEffect(Unit) {
        delay(50)
        isVisible = true
        isRecording = true
        onStart()
    }

    LaunchedEffect(shouldDismiss) {
        if (shouldDismiss) {
            isVisible = false
            delay(250)
            onDismissComplete()
        }
    }

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

    AnimatedVisibility(
        visible = isVisible,
        enter = enterTransition,
        exit = exitTransition
    ) {
        VoiceRecognitionDialogContent(
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
                MainScope().launch {
                    delay(250)
                    onCancel()
                }
            },
            onToggleLlm = onToggleLlm
        )
    }
}

@Composable
private fun VoiceRecognitionDialogContent(
    recognitionState: RecognitionState,
    partialTranscript: String,
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onToggleLlm: (Boolean) -> Unit
) {
    val containerSize by animateDpAsState(
        targetValue = if (recognitionState == RecognitionState.Listening) 80.dp else 72.dp,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "mic_container_size"
    )

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
                Box(
                    modifier = Modifier.size(containerSize),
                    contentAlignment = Alignment.Center
                ) {
                    BreathingPulse(
                        isActive = recognitionState == RecognitionState.Listening,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        baseSize = 72.dp,
                        expandedSize = containerSize + 16.dp
                    )

                    if (recognitionState == RecognitionState.Processing) {
                        ThinkingDots(
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = 24.dp)
                        )
                    }

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
                    RecognitionState.Idle -> Unit
                }

                if (partialTranscript.isNotBlank()) {
                    Text(
                        text = partialTranscript,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                VoiceRecognitionLlmControlSection(
                    llmEnabled = llmEnabled,
                    currentMode = currentMode,
                    isRecording = recognitionState == RecognitionState.Listening,
                    onToggleLlm = onToggleLlm
                )

                VoiceRecognitionActionButton(
                    recognitionState = recognitionState,
                    onStart = onStart,
                    onStop = onStop
                )
            }
        }
    }
}

@Composable
private fun VoiceRecognitionLlmControlSection(
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    isRecording: Boolean,
    onToggleLlm: (Boolean) -> Unit
) {
    if (!llmEnabled) {
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
            Box(
                modifier = Modifier.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
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
private fun VoiceRecognitionActionButton(
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
