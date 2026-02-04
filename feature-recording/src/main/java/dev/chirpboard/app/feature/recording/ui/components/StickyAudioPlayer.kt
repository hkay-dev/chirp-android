package dev.chirpboard.app.feature.recording.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    modifier: Modifier = Modifier
) {
    val isIdle = playbackState is PlaybackState.Idle
    val isLoading = playbackState is PlaybackState.Loading
    val isError = playbackState is PlaybackState.Error
    val isPlaying = playbackState is PlaybackState.Playing
    val controlsEnabled = !isIdle && !isLoading && !isError

    val positionMs = when (playbackState) {
        is PlaybackState.Playing -> playbackState.positionMs
        is PlaybackState.Paused -> playbackState.positionMs
        else -> 0L
    }

    val durationMs = when (playbackState) {
        is PlaybackState.Playing -> playbackState.durationMs
        is PlaybackState.Paused -> playbackState.durationMs
        else -> 0L
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Seek bar with time labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = positionMs.formatAsDuration(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
                    onValueChange = { fraction ->
                        onSeek((fraction * durationMs).toLong())
                    },
                    enabled = controlsEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )
                Text(
                    text = durationMs.formatAsDuration(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Controls row - centered with prominent play button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Skip backward 10s
                IconButton(
                    onClick = onSkipBackward,
                    enabled = controlsEnabled,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay10,
                        contentDescription = "Skip backward 10 seconds",
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Large prominent Play/Pause button
                FilledIconButton(
                    onClick = onPlayPause,
                    enabled = controlsEnabled && !isLoading,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .size(64.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        isError -> {
                            Icon(
                                imageVector = Icons.Filled.Error,
                                contentDescription = "Playback error",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        isPlaying -> {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = "Pause",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                // Skip forward 10s
                IconButton(
                    onClick = onSkipForward,
                    enabled = controlsEnabled,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward10,
                        contentDescription = "Skip forward 10 seconds",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
