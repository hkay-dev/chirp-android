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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.ThinkingDots
import dev.chirpboard.app.core.recording.WaveformBuffer
import dev.chirpboard.app.core.ui.components.recording.AudioWaveform
import dev.chirpboard.app.core.ui.components.recording.RecordingGlowBackground
import dev.chirpboard.app.core.ui.components.recording.RecordingTimer
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay

@Composable
fun VoiceRecognitionDialog(
    waveformBuffer: WaveformBuffer,
    sampleCountFlow: StateFlow<Long>,
    recordingStateFlow: StateFlow<RecordingState>,
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
    val recordingState by recordingStateFlow.collectAsStateWithLifecycle(RecordingState.Idle)
    val shouldDismiss by shouldDismissFlow.collectAsStateWithLifecycle(false)
    val partialTranscript by partialTranscriptFlow.collectAsStateWithLifecycle("")
    var isVisible by remember { mutableStateOf(true) }

    val sampleCount by sampleCountFlow.collectAsStateWithLifecycle(0L)

    LaunchedEffect(Unit) {
        onStart()
    }

    LaunchedEffect(shouldDismiss) {
        if (shouldDismiss) {
            isVisible = false
            delay(VOICE_RECOGNITION_EXIT_MS)
            onDismissComplete()
        }
    }

    val enterTransition =
        fadeIn(animationSpec = tween(200)) +
            slideInVertically(
                initialOffsetY = { it },
                animationSpec =
                    spring(
                        dampingRatio = 0.8f,
                        stiffness = 400f,
                    ),
            )

    val exitTransition =
        fadeOut(animationSpec = tween(150)) +
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec =
                    spring(
                        dampingRatio = 0.9f,
                        stiffness = 400f,
                    ),
            )

    Box(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        contentAlignment = Alignment.BottomCenter,
    ) {

        AnimatedVisibility(
            visible = isVisible,
            enter = enterTransition,
            exit = exitTransition,
        ) {
            VoiceRecognitionDialogContent(
                recordingState = recordingState,
                waveformBuffer = waveformBuffer,
                sampleCount = sampleCount,
                partialTranscript = partialTranscript,
                llmEnabled = llmEnabled,
                currentMode = currentMode,
                onStart = {
                    onStart()
                },
                onStop = {
                    onStop()
                },
                onCancel = onCancel,
                onToggleLlm = onToggleLlm,
            )
        }
    }
}

@Composable
private fun VoiceRecognitionDialogContent(
    recordingState: RecordingState,
    waveformBuffer: WaveformBuffer,
    sampleCount: Long,
    partialTranscript: String,
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onToggleLlm: (Boolean) -> Unit,
) {
    val isRecording = recordingState is RecordingState.Recording || recordingState is RecordingState.Starting || recordingState is RecordingState.Stopping
    val isProcessing = recordingState is RecordingState.Stopping
    val showRecordingVisuals = isRecording && !isProcessing
    val recordingVisualEnter =
        fadeIn(tween(ChirpMotion.STUDIO_REVEAL_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
            expandVertically(
                animationSpec = tween(ChirpMotion.STUDIO_REVEAL_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            )
    val recordingVisualExit =
        fadeOut(tween(ChirpMotion.STUDIO_HIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
            shrinkVertically(
                animationSpec = tween(ChirpMotion.STUDIO_HIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            )
    val containerSize by animateDpAsState(
        targetValue = if (isRecording && !isProcessing) 96.dp else 80.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "mic_container_size",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 280.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(), // Ensures we draw above the system nav bar
        ) {
            IconButton(
                onClick = onCancel,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.desc_cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = showRecordingVisuals,
                enter = fadeIn(tween(ChirpMotion.STUDIO_REVEAL_MS)),
                exit = fadeOut(tween(ChirpMotion.STUDIO_HIDE_MS)),
            ) {
                RecordingGlowBackground(modifier = Modifier.fillMaxSize())
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .animatePushDownLayout()
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = partialTranscript,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
                        },
                        label = "transcript_animation",
                    ) { text ->
                        if (text.isNotBlank()) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                            )
                        } else if (isRecording && !isProcessing) {
                            RecordingTimer(
                                recordingState = recordingState,
                                isRecording = true,
                                textStyle = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                                    letterSpacing = 2.sp,
                                )
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showRecordingVisuals,
                    enter = recordingVisualEnter,
                    exit = recordingVisualExit,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AudioWaveform(
                            waveformBuffer = waveformBuffer,
                            sampleCount = sampleCount,
                            isActive = true,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            maxBarHeight = 64.dp,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(if (showRecordingVisuals) 0.dp else 32.dp))

                // Mic Button Area
                Box(
                    modifier =
                        Modifier
                            .size(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val buttonColor =
                        if (isRecording && !isProcessing) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    val iconColor =
                        if (isRecording && !isProcessing) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }

                    Box(
                        modifier =
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(buttonColor)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = false, radius = 56.dp),
                                    enabled = !isProcessing,
                                    onClick = {
                                        if (isRecording) {
                                            onStop()
                                        } else {
                                            onStart()
                                        }
                                    },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isProcessing) {
                            ThinkingDots(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Icon(
                                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription =
                                    if (isRecording) {
                                        stringResource(R.string.desc_stop)
                                    } else {
                                        stringResource(R.string.desc_start)
                                    },
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
                    isRecording = isRecording,
                    onToggleLlm = onToggleLlm,
                )
            }
        }
    }
}

private const val VOICE_RECOGNITION_EXIT_MS = 250L

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
                text =
                    if (llmEnabled) {
                        stringResource(R.string.voice_recognition_llm_mode, currentMode.displayName)
                    } else {
                        stringResource(R.string.voice_recognition_llm_enable)
                    },
                style = MaterialTheme.typography.labelLarge,
            )
        },
        leadingIcon =
            if (llmEnabled) {
                {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
            } else {
                null
            },
    )
}
