package dev.chirpboard.app.feature.recording.ui.studio.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.runtime.*
import dev.chirpboard.app.feature.recording.ui.components.formatTimeMs
import kotlin.math.max

@Composable
fun FloatingAudioPlayer(
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var localPosition by remember(currentPositionMs, isPlaying) { 
        mutableLongStateOf(currentPositionMs) 
    }

    LaunchedEffect(isPlaying, durationMs) {
        if (isPlaying && durationMs > 0) {
            var lastTime = withFrameMillis { it }
            while (true) {
                val currentTime = withFrameMillis { it }
                val delta = currentTime - lastTime
                lastTime = currentTime
                localPosition = (localPosition + delta).coerceAtMost(durationMs)
            }
        }
    }

    val progress = if (durationMs > 0) {
        (localPosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(36.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlayPauseClick) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatTimeMs(localPosition),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.width(8.dp))

            Slider(
                value = progress,
                onValueChange = { newProgress ->
                    val newPos = (newProgress * durationMs).toLong()
                    localPosition = newPos
                    onSeek(newPos)
                },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatTimeMs(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}