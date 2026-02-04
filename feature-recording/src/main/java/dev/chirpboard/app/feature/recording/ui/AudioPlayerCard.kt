package dev.chirpboard.app.feature.recording.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.feature.recording.audio.PlaybackState

@Composable
fun AudioPlayerCard(
    playbackState: PlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            when (playbackState) {
                is PlaybackState.Idle -> {
                    Text(
                        text = "No audio loaded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                is PlaybackState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Loading audio...")
                    }
                }
                
                is PlaybackState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = playbackState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                
                is PlaybackState.Playing, is PlaybackState.Paused -> {
                    val isPlaying = playbackState is PlaybackState.Playing
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
                    
                    // Time display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = positionMs.formatAsDuration(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = durationMs.formatAsDuration(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // Progress slider
                    Slider(
                        value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                        onValueChange = { fraction ->
                            val newPosition = (fraction * durationMs).toLong()
                            onSeek(newPosition)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Skip backward
                        IconButton(onClick = onSkipBackward) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Skip back 10 seconds"
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        // Play/Pause
                        FilledIconButton(
                            onClick = onPlayPause,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        // Skip forward
                        IconButton(onClick = onSkipForward) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Skip forward 10 seconds"
                            )
                        }
                    }
                }
            }
        }
    }
}
