package dev.chirpboard.app.core.ui.playback

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.R
import dev.chirpboard.app.core.util.formatAsDuration

@Composable
internal fun playbackSliderColors() =
    SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )

@Composable
internal fun PlaybackTimelineRow(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val progressFraction =
        if (durationMs > 0) {
            if (isDragging) dragFraction else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    val displayedPositionMs =
        if (isDragging) {
            (dragFraction * durationMs).toLong()
        } else {
            positionMs
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Slider(
            value = progressFraction,
            onValueChange = { fraction ->
                isDragging = true
                dragFraction = fraction
                onSeek((fraction * durationMs).toLong())
            },
            onValueChangeFinished = { isDragging = false },
            enabled = enabled && durationMs > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = playbackSliderColors(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayedPositionMs.formatAsDuration(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = durationMs.formatAsDuration(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PlaybackTransportRow(
    isLoading: Boolean,
    isError: Boolean,
    isPlaying: Boolean,
    controlsEnabled: Boolean,
    onPlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    playButtonSize: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onSkipBackward,
            enabled = controlsEnabled,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Replay10,
                contentDescription = stringResource(R.string.playback_skip_back),
                modifier = Modifier.size(22.dp),
            )
        }

        FilledTonalIconButton(
            onClick = onPlayPause,
            enabled = !isLoading && !isError,
            modifier = Modifier.size(playButtonSize),
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                }

                isError -> {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = stringResource(R.string.playback_error),
                        modifier = Modifier.size(24.dp),
                    )
                }

                else -> {
                    Crossfade(
                        targetState = isPlaying,
                        animationSpec = tween(160),
                        label = "playPauseIcon",
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription =
                                if (playing) {
                                    stringResource(R.string.playback_pause)
                                } else {
                                    stringResource(R.string.playback_play)
                                },
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onSkipForward,
            enabled = controlsEnabled,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Forward10,
                contentDescription = stringResource(R.string.playback_skip_forward),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
internal fun PlaybackNoticeBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun MiniPlayerProgressTrack(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val fraction =
        if (durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val activeColor = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(trackColor),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(activeColor),
        )
    }
}

@Composable
internal fun MiniPlayerSeekTrack(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val progressFraction =
        if (durationMs > 0) {
            if (isDragging) dragFraction else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    val displayedPositionMs =
        if (isDragging) {
            (dragFraction * durationMs).toLong()
        } else {
            positionMs
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiniPlayerProgressTrack(
            positionMs = displayedPositionMs,
            durationMs = durationMs,
            modifier = Modifier.fillMaxWidth(),
        )
        Slider(
            value = progressFraction,
            onValueChange = { fraction ->
                isDragging = true
                dragFraction = fraction
                onSeek((fraction * durationMs).toLong())
            },
            onValueChangeFinished = { isDragging = false },
            enabled = enabled && durationMs > 0,
            modifier = Modifier.fillMaxWidth(),
            colors =
                SliderDefaults.colors(
                    thumbColor =
                        if (isDragging) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    disabledThumbColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                ),
        )
    }
}
