package dev.chirpboard.app.feature.keyboard.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.FastOutSlowInEasing
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.ui.components.ThinkingDots
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.feature.keyboard.R
import dev.chirpboard.app.feature.keyboard.state.KeyboardState
import dev.chirpboard.app.feature.keyboard.theme.KeyboardTheme
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import dev.chirpboard.app.core.recording.WaveformBuffer
import dev.chirpboard.app.core.ui.components.recording.AudioWaveform
import dev.chirpboard.app.core.ui.components.recording.RecordingGlowBackground

private enum class ProcessingPhase {
    Transcribing,
    Polishing,
}

private enum class KeyboardAnimatedTarget {
    Idle,
    Recording,
    Processing,
    Downloading,
    ModelNotReady,
    Error,
    LlmError,
}

private fun KeyboardState.toAnimatedTarget(): KeyboardAnimatedTarget =
    when (this) {
        is KeyboardState.Idle -> KeyboardAnimatedTarget.Idle
        is KeyboardState.Recording -> KeyboardAnimatedTarget.Recording
        is KeyboardState.Transcribing,
        is KeyboardState.Polishing,
        -> KeyboardAnimatedTarget.Processing
        is KeyboardState.Downloading -> KeyboardAnimatedTarget.Downloading
        is KeyboardState.ModelNotReady -> KeyboardAnimatedTarget.ModelNotReady
        is KeyboardState.Error -> KeyboardAnimatedTarget.Error
        is KeyboardState.LlmError -> KeyboardAnimatedTarget.LlmError
    }

private fun KeyboardState.processingPhase(): ProcessingPhase =
    if (this is KeyboardState.Polishing) ProcessingPhase.Polishing else ProcessingPhase.Transcribing

@Composable
fun KeyboardUI(
    state: KeyboardState,
    waveformBuffer: WaveformBuffer,
    sampleCountFlow: StateFlow<Long>,
    llmEnabled: Boolean,
    currentMode: ProcessingMode?,
    onTap: () -> Unit,
    onCancel: () -> Unit = {},
    onRestart: () -> Unit = {},
    onToggleLlm: () -> Unit,
    onModeChange: (ProcessingMode) -> Unit,
    onBackspace: () -> Unit = {},
    onSpace: () -> Unit = {},
    onMoveCursor: (Int) -> Unit = {},
    onOpenApp: () -> Unit = {},
) {
    KeyboardTheme {
        val modeScrollState = rememberScrollState()
        val showModeControls =
            currentMode != null &&
                (state is KeyboardState.Idle || state is KeyboardState.Recording)
        val outlineColor = MaterialTheme.colorScheme.outlineVariant
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 280.dp)
                    .drawBehind {
                        drawLine(
                            color = outlineColor,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                    },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = 1.dp).animatePushDownLayout(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val animatedTarget = state.toAnimatedTarget()
                    AnimatedContent(
                        targetState = animatedTarget,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))
                        },
                        label = "keyboardStateTransition",
                    ) { target ->
                        when (target) {
                            KeyboardAnimatedTarget.Idle -> {
                                IdleContent(
                                    onTap = onTap,
                                    onBackspace = onBackspace,
                                    onSpace = onSpace,
                                    onMoveCursor = onMoveCursor,
                                )
                            }

                            KeyboardAnimatedTarget.Recording -> {
                                RecordingContent(
                                    waveformBuffer = waveformBuffer,
                                    sampleCountFlow = sampleCountFlow,
                                    onStop = onTap,
                                    onCancel = onCancel,
                                    onRestart = onRestart,
                                )
                            }

                            KeyboardAnimatedTarget.Processing -> {
                                KeyboardProcessingContent(state.processingPhase())
                            }

                            KeyboardAnimatedTarget.Downloading -> {
                                val downloading = state as? KeyboardState.Downloading
                                DownloadingContent(downloading?.progress ?: 0f)
                            }

                            KeyboardAnimatedTarget.ModelNotReady -> {
                                ModelNotReadyContent(onTap = onTap, onOpenApp = onOpenApp)
                            }

                            KeyboardAnimatedTarget.Error -> {
                                val error = state as? KeyboardState.Error
                                ErrorContent(error?.message.orEmpty(), onTap)
                            }

                            KeyboardAnimatedTarget.LlmError -> {
                                val llmError = state as? KeyboardState.LlmError
                                LlmErrorContent(llmError?.message.orEmpty(), onTap)
                            }
                        }
                    }
                }

                PushDownReveal(visible = showModeControls) {
                    ModeControlsRow(
                        scrollState = modeScrollState,
                        llmEnabled = llmEnabled,
                        currentMode = currentMode!!,
                        onToggleLlm = onToggleLlm,
                        onModeChange = onModeChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeControlsRow(
    scrollState: ScrollState,
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    onToggleLlm: () -> Unit,
    onModeChange: (ProcessingMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LlmToggle(
            enabled = llmEnabled,
            currentMode = currentMode,
            onClick = onToggleLlm,
        )
        if (llmEnabled) {
            Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
            ModeSelector(
                scrollState = scrollState,
                currentMode = currentMode,
                onModeChange = onModeChange,
            )
        }
    }
}

@Composable
private fun LlmToggle(
    enabled: Boolean,
    currentMode: ProcessingMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = enabled,
        onClick = onClick,
        label = {
            Text(
                text =
                    if (enabled) {
                        stringResource(R.string.keyboard_llm_mode, currentMode.displayName)
                    } else {
                        stringResource(R.string.keyboard_llm_enable)
                    },
                style = MaterialTheme.typography.labelMedium,
            )
        },
        leadingIcon =
            if (enabled) {
                {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
            } else {
                null
            },
        modifier = modifier,
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
    )
}

@Composable
private fun IdleContent(
    onTap: () -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onMoveCursor: (Int) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LargeFloatingActionButton(
            onClick = onTap,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                Icons.Filled.Mic,
                stringResource(R.string.keyboard_desc_start_recording),
                Modifier.size(36.dp),
            )
        }
        Text(
            stringResource(R.string.keyboard_tap_to_speak),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        KeyboardControls(
            onBackspace = onBackspace,
            onSpace = onSpace,
            onMoveCursor = onMoveCursor,
        )
    }
}

@Composable
private fun KeyboardControls(
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onMoveCursor: (Int) -> Unit,
) {
    val dragOffset = remember { mutableFloatStateOf(0f) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Backspace button
        IconButton(
            onClick = onBackspace,
            modifier =
                Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        ChirpShapes.Small,
                    ).size(48.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Backspace,
                contentDescription = stringResource(R.string.keyboard_desc_delete),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        // Space button with swipe gesture
        FilledTonalButton(
            onClick = onSpace,
            modifier =
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragOffset.floatValue = 0f },
                            onDragEnd = {
                                // Reset offset
                                dragOffset.floatValue = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                dragOffset.floatValue += dragAmount
                                // Move cursor when drag exceeds threshold
                                val threshold = 30f
                                if (kotlin.math.abs(dragOffset.floatValue) >= threshold) {
                                    val direction = if (dragOffset.floatValue > 0) 1 else -1
                                    onMoveCursor(direction)
                                    dragOffset.floatValue = 0f
                                }
                            },
                        )
                    },
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        ) {
            Icon(
                Icons.Filled.SpaceBar,
                contentDescription = stringResource(R.string.keyboard_desc_space),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.keyboard_desc_space), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ModeSelector(
    scrollState: ScrollState,
    currentMode: ProcessingMode,
    onModeChange: (ProcessingMode) -> Unit,
) {
    val currentId = currentMode.id

    Row(
        modifier = Modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        ModeChip("proofread", stringResource(R.string.keyboard_mode_proofread), currentId, onModeChange)
        ModeChip("formal", stringResource(R.string.keyboard_mode_formal), currentId, onModeChange)
        ModeChip("casual", stringResource(R.string.keyboard_mode_casual), currentId, onModeChange)
        ModeChip("email", stringResource(R.string.keyboard_mode_email), currentId, onModeChange)
        ModeChip("code", stringResource(R.string.keyboard_mode_code), currentId, onModeChange)
        ModeChip("smart", stringResource(R.string.keyboard_mode_smart), currentId, onModeChange)
    }
}

@Composable
private fun ModeChip(
    id: String,
    label: String,
    currentId: String?,
    onModeChange: (ProcessingMode) -> Unit,
) {
    val mode = remember(id) { ProcessingMode.fromId(id) }
    FilterChip(
        selected = currentId == id,
        onClick = { onModeChange(mode) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.height(28.dp),
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    )
}

@Composable
private fun RecordingContent(
    waveformBuffer: WaveformBuffer,
    sampleCountFlow: StateFlow<Long>,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRestart: () -> Unit,
) {
    val sampleCount by sampleCountFlow.collectAsStateWithLifecycle()
    val elapsedMs = (sampleCount * 1000L) / VoiceRecorder.SAMPLE_RATE
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val scale =
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOutQuad), RepeatMode.Reverse),
            label = "pulse",
        )
    Box(modifier = Modifier.fillMaxSize()) {
        RecordingGlowBackground(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = elapsedMs.formatAsDuration(),
                style =
                    MaterialTheme.typography.displaySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp,
                    ),
                color = MaterialTheme.colorScheme.error,
            )
            AudioWaveform(
                waveformBuffer = waveformBuffer,
                sampleCount = sampleCount,
                isActive = true,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
                minBarHeight = 4.dp,
                maxBarHeight = 64.dp,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, ChirpShapes.Small).size(48.dp),
                ) {
                    Icon(Icons.Filled.Close, "Cancel recording", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                LargeFloatingActionButton(
                    onClick = onStop,
                    modifier =
                        Modifier.graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Icon(Icons.Filled.Stop, stringResource(R.string.keyboard_desc_stop_recording), Modifier.size(36.dp))
                }

                IconButton(
                    onClick = onRestart,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, ChirpShapes.Small).size(48.dp),
                ) {
                    Icon(Icons.Filled.Refresh, "Restart recording", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun KeyboardProcessingContent(phase: ProcessingPhase) {
    AnimatedContent(
        targetState = phase,
        transitionSpec = { ChirpMotion.keyboardProcessingCrossfade },
        label = "keyboardProcessingPhase",
    ) { currentPhase ->
        val message =
            when (currentPhase) {
                ProcessingPhase.Transcribing -> stringResource(R.string.keyboard_transcribing)
                ProcessingPhase.Polishing -> stringResource(R.string.keyboard_polishing)
            }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ThinkingDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DownloadingContent(progress: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(
            stringResource(R.string.keyboard_downloading_model_progress, (progress * 100).toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onTap: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        FilledTonalButton(
            onClick = onTap,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.keyboard_retry))
        }
    }
}

@Composable
private fun LlmErrorContent(
    message: String,
    onDismiss: () -> Unit = {},
) {
    LaunchedEffect(message) {
        delay(3000)
        onDismiss()
    }
    Row(
        modifier =
            Modifier
                .padding(horizontal = 24.dp)
                .minimumInteractiveComponentSize()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.errorContainer)
                .semantics(mergeDescendants = true) {}
                .clickable { onDismiss() }
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun ModelNotReadyContent(
    onTap: () -> Unit,
    onOpenApp: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            Modifier
                .size(48.dp)
                .clickable(onClick = onTap),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.keyboard_model_not_ready),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onOpenApp) {
            Text(stringResource(R.string.keyboard_open_app_to_download))
        }
    }
}
