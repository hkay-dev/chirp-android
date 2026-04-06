package dev.chirpboard.app.feature.keyboard.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.feature.keyboard.R
import dev.chirpboard.app.feature.keyboard.state.KeyboardState
import dev.chirpboard.app.feature.keyboard.theme.KeyboardTheme
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
fun KeyboardUI(
    state: KeyboardState,
    amplitudes: StateFlow<ImmutableList<Float>>,
    llmEnabled: Boolean,
    currentMode: ProcessingMode?,
    onTap: () -> Unit,
    onToggleLlm: () -> Unit,
    onModeChange: (ProcessingMode) -> Unit,
    onBackspace: () -> Unit = {},
    onSpace: () -> Unit = {},
    onMoveCursor: (Int) -> Unit = {},
) {
    KeyboardTheme {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 280.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // LLM Toggle in top-right corner
                LlmToggle(
                    enabled = llmEnabled,
                    onClick = onToggleLlm,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                )

                // Main content
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))
                        },
                        label = "keyboardStateTransition",
                    ) { currentState ->
                        when (currentState) {
                            is KeyboardState.Idle -> {
                                IdleContent(
                                    onTap = onTap,
                                    llmEnabled = llmEnabled,
                                    currentMode = currentMode,
                                    onModeChange = onModeChange,
                                    onBackspace = onBackspace,
                                    onSpace = onSpace,
                                    onMoveCursor = onMoveCursor,
                                )
                            }

                            is KeyboardState.Recording -> {
                                RecordingContent(amplitudes, onTap)
                            }

                            is KeyboardState.Transcribing -> {
                                ProcessingContent("Transcribing...")
                            }

                            is KeyboardState.Polishing -> {
                                ProcessingContent("Polishing...")
                            }

                            is KeyboardState.Downloading -> {
                                DownloadingContent(currentState.progress)
                            }

                            is KeyboardState.ModelNotReady -> {
                                ModelNotReadyContent()
                            }

                            is KeyboardState.Error -> {
                                ErrorContent(currentState.message, onTap)
                            }

                            is KeyboardState.LlmError -> {
                                LlmErrorContent(currentState.message, onTap)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LlmToggle(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = enabled,
        onClick = onClick,
        label = { Text("LLM", style = MaterialTheme.typography.labelMedium) },
        leadingIcon =
            if (enabled) {
                { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
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
    llmEnabled: Boolean,
    currentMode: ProcessingMode?,
    onModeChange: (ProcessingMode) -> Unit,
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
            Icon(Icons.Filled.Mic, "Start recording", Modifier.size(36.dp))
        }
        Text("Tap to speak", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Keyboard controls
        KeyboardControls(
            onBackspace = onBackspace,
            onSpace = onSpace,
            onMoveCursor = onMoveCursor,
        )

        // Mode selector - only show when LLM is enabled
        if (llmEnabled && currentMode != null) {
            ModeSelector(
                currentMode = currentMode,
                onModeChange = onModeChange,
            )
        }
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
                Icons.Filled.Backspace,
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
    currentMode: ProcessingMode?,
    onModeChange: (ProcessingMode) -> Unit,
) {
    val scrollState = rememberScrollState()
    val currentId = currentMode?.id

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        ModeChip("proofread", "Proofread", currentId, onModeChange)
        ModeChip("formal", "Formal", currentId, onModeChange)
        ModeChip("casual", "Casual", currentId, onModeChange)
        ModeChip("email", "Email", currentId, onModeChange)
        ModeChip("code", "Code", currentId, onModeChange)
        ModeChip("smart", "Smart", currentId, onModeChange)
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
    amplitudes: StateFlow<ImmutableList<Float>>,
    onTap: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val scale =
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOutQuad), RepeatMode.Reverse),
            label = "pulse",
        )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Waveform visualization
        WaveformVisualizer(
            amplitudesFlow = amplitudes,
            modifier = Modifier.height(40.dp),
            barColor = MaterialTheme.colorScheme.error,
        )

        LargeFloatingActionButton(
            onClick = onTap,
            modifier =
                Modifier.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Icon(Icons.Filled.Stop, "Stop recording", Modifier.size(36.dp))
        }
        Text("Tap to stop", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ProcessingContent(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeWidth = 4.dp,
        )
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            "Downloading model... ${(progress * 100).toInt()}%",
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
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.errorContainer)
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
private fun ModelNotReadyContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Model not ready", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("Open app to download", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
