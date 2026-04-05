package dev.chirpboard.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.components.BreathingPulse
import dev.chirpboard.app.core.ui.components.ThinkingDots
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class RecognitionState {
    Idle,
    Listening,
    Processing,
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
    onToggleLlm: (Boolean) -> Unit,
) {
    var isRecording by remember { mutableStateOf(false) }
    val amplitudes by amplitudesFlow.collectAsStateWithLifecycle(emptyList())
    val isProcessing by isProcessingFlow.collectAsStateWithLifecycle(false)
    val shouldDismiss by shouldDismissFlow.collectAsStateWithLifecycle(false)
    val partialTranscript by partialTranscriptFlow.collectAsStateWithLifecycle("")
    var isVisible by remember { mutableStateOf(false) }

    val recognitionState =
        when {
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

    val enterTransition =
        fadeIn(animationSpec = tween(200)) +
            slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = 400f
                ),
            )

    val exitTransition =
        fadeOut(animationSpec = tween(150)) +
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = 0.9f,
                    stiffness = 400f
                ),
            )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Scrim background to dim the app behind the STT overlay
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(250)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            isVisible = false
                            MainScope().launch {
                                delay(250)
                                onCancel()
                            }
                        }
                    )
            )
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = enterTransition,
            exit = exitTransition,
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
                onToggleLlm = onToggleLlm,
            )
        }
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
    onToggleLlm: (Boolean) -> Unit,
) {
    val containerSize by animateDpAsState(
        targetValue = if (recognitionState == RecognitionState.Listening) 96.dp else 80.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "mic_container_size",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 6.dp, // Premium native feel
        shadowElevation = 16.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding() // Ensures we draw above the system nav bar
        ) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = 32.dp,
                        bottom = 24.dp,
                        start = 24.dp,
                        end = 24.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Transcript Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = partialTranscript,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
                        },
                        label = "transcript_animation"
                    ) { text ->
                        if (text.isNotBlank()) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                            )
                        } else if (recognitionState == RecognitionState.Listening) {
                            Text(
                                text = "Listening...",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Mic Button Area
                Box(
                    modifier = Modifier
                        .size(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (recognitionState == RecognitionState.Listening) {
                        BreathingPulse(
                            isActive = true,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            baseSize = 80.dp,
                            expandedSize = containerSize + 24.dp,
                        )
                    }

                    val buttonColor = if (recognitionState == RecognitionState.Listening) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                    val iconColor = if (recognitionState == RecognitionState.Listening) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(buttonColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = false, radius = 56.dp),
                                enabled = recognitionState != RecognitionState.Processing,
                                onClick = {
                                    if (recognitionState == RecognitionState.Listening) {
                                        onStop()
                                    } else {
                                        onStart()
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (recognitionState == RecognitionState.Processing) {
                            ThinkingDots(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Icon(
                                imageVector = if (recognitionState == RecognitionState.Listening) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = if (recognitionState == RecognitionState.Listening) "Stop" else "Start",
                                modifier = Modifier.size(32.dp),
                                tint = iconColor,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // LLM Control Chip
                VoiceRecognitionLlmControlSection(
                    llmEnabled = llmEnabled,
                    currentMode = currentMode,
                    isRecording = recognitionState == RecognitionState.Listening,
                    onToggleLlm = onToggleLlm,
                )
            }
        }
    }
}

@Composable
private fun VoiceRecognitionLlmControlSection(
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    isRecording: Boolean, // Can still toggle while recording
    onToggleLlm: (Boolean) -> Unit,
) {
    FilterChip(
        selected = llmEnabled,
        onClick = { onToggleLlm(!llmEnabled) },
        label = {
            Text(
                text = if (llmEnabled) "AI: ${currentMode.displayName}" else "Enhance with AI",
                style = MaterialTheme.typography.labelLarge
            )
        },
        leadingIcon = if (llmEnabled) {
            {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else null,
    )
}
