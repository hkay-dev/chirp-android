package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.feature.recording.audio.PlaybackState

@Composable
fun StickyAudioPlayer(
    playbackState: PlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isIdle = playbackState is PlaybackState.Idle
    val isLoading = playbackState is PlaybackState.Loading
    val isError = playbackState is PlaybackState.Error
    val isPlaying = playbackState is PlaybackState.Playing
    val controlsEnabled = !isIdle && !isLoading && !isError

    val positionMs =
        when (playbackState) {
            is PlaybackState.Playing -> playbackState.positionMs
            is PlaybackState.Paused -> playbackState.positionMs
            else -> 0L
        }

    val durationMs =
        when (playbackState) {
            is PlaybackState.Playing -> playbackState.durationMs
            is PlaybackState.Paused -> playbackState.durationMs
            else -> 0L
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Seek bar with time labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = positionMs.formatAsDuration(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val sliderValue = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
                val animatedSliderValue by animateFloatAsState(
                    targetValue = sliderValue,
                    animationSpec = tween(100, easing = FastOutSlowInEasing),
                    label = "sliderPosition",
                )
                Slider(
                    value = animatedSliderValue,
                    onValueChange = { fraction ->
                        onSeek((fraction * durationMs).toLong())
                    },
                    enabled = controlsEnabled,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    colors =
                        SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
                Text(
                    text = durationMs.formatAsDuration(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Controls row - centered with prominent play button
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // Skip backward 10s
                var isSkipBackPressed by remember { mutableStateOf(false) }
                val skipBackScale by animateFloatAsState(
                    targetValue = if (isSkipBackPressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                    label = "skipBackButtonScale",
                )
                IconButton(
                    onClick = onSkipBackward,
                    enabled = controlsEnabled,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .scale(skipBackScale)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        isSkipBackPressed = event.type == PointerEventType.Press
                                    }
                                }
                            },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay10,
                        contentDescription = "Skip backward 10 seconds",
                        modifier = Modifier.size(28.dp),
                    )
                }

                // Large prominent Play/Pause button
                FilledIconButton(
                    onClick = onPlayPause,
                    enabled = controlsEnabled && !isLoading,
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp)
                            .size(64.dp),
                    shape = CircleShape,
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }

                        isError -> {
                            Icon(
                                imageVector = Icons.Filled.Error,
                                contentDescription = "Playback error",
                                modifier = Modifier.size(32.dp),
                            )
                        }

                        else -> {
                            Crossfade(
                                targetState = isPlaying,
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                                label = "playPauseIcon",
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                    }
                }

                // Skip forward 10s
                var isSkipForwardPressed by remember { mutableStateOf(false) }
                val skipForwardScale by animateFloatAsState(
                    targetValue = if (isSkipForwardPressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                    label = "skipForwardButtonScale",
                )
                IconButton(
                    onClick = onSkipForward,
                    enabled = controlsEnabled,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .scale(skipForwardScale)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        isSkipForwardPressed = event.type == PointerEventType.Press
                                    }
                                }
                            },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward10,
                        contentDescription = "Skip forward 10 seconds",
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}
