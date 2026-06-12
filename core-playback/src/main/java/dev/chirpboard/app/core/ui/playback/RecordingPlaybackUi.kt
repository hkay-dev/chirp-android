package dev.chirpboard.app.core.ui.playback

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.playback.R as PlaybackR
import dev.chirpboard.app.core.ui.R
import dev.chirpboard.app.core.util.formatAsDuration

/** Tabular-figure time readout style so advancing playback digits do not jitter (PRM-8). */
@Composable
private fun timeReadoutStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")

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

    // TalkBack reads the raw 0..1 fraction as a bare percentage; describe the position
    // in time instead ("1:20 of 3:42").
    val seekStateDescription =
        stringResource(
            PlaybackR.string.playback_seek_position,
            displayedPositionMs.formatAsDuration(),
            durationMs.formatAsDuration(),
        )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Slider(
            value = progressFraction,
            onValueChange = { fraction ->
                // Only track the fraction for live display; defer the media seek to
                // onValueChangeFinished so a scrub does not fire one seekTo per drag frame.
                isDragging = true
                dragFraction = fraction
            },
            onValueChangeFinished = {
                onSeek((dragFraction * durationMs).toLong())
                isDragging = false
            },
            enabled = enabled && durationMs > 0,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = seekStateDescription },
            colors = playbackSliderColors(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayedPositionMs.formatAsDuration(),
                style = timeReadoutStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = durationMs.formatAsDuration(),
                style = timeReadoutStyle(),
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
        // No fixed 40dp size: the M3 default keeps the 40dp visual state layer while
        // restoring the 48dp minimum interactive bounds (a11y touch-target audit).
        IconButton(
            onClick = onSkipBackward,
            enabled = controlsEnabled,
        ) {
            Icon(
                imageVector = Icons.Rounded.Replay10,
                contentDescription = stringResource(R.string.playback_skip_back),
                modifier = Modifier.size(22.dp),
            )
        }

        FilledTonalIconButton(
            onClick = onPlayPause,
            enabled = !isLoading && !isError,
            modifier = Modifier.minimumInteractiveComponentSize().size(playButtonSize),
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
                        imageVector = Icons.Rounded.Error,
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
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
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
        ) {
            Icon(
                imageVector = Icons.Rounded.Forward10,
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val miniSeekStateDescription =
        stringResource(
            PlaybackR.string.playback_seek_position,
            displayedPositionMs.formatAsDuration(),
            durationMs.formatAsDuration(),
        )

    // 32dp hit area (was 16dp): the visual 2dp track stays centered, but a sliver this
    // thin was nearly impossible to grab by touch or focus with TalkBack/switch access.
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(32.dp),
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
                // Only track the fraction for live display; defer the media seek to
                // onValueChangeFinished so a scrub does not fire one seekTo per drag frame.
                isDragging = true
                dragFraction = fraction
            },
            onValueChangeFinished = {
                onSeek((dragFraction * durationMs).toLong())
                isDragging = false
            },
            enabled = enabled && durationMs > 0,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = miniSeekStateDescription },
            colors =
                SliderDefaults.colors(
                    // The custom MiniPlayerProgressTrack draws the visible track, so keep the
                    // Slider's own track transparent; only the custom thumb is rendered.
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                ),
            // PRM-5: a resting thumb so the hairline reads as scrubbable instead of a dead 2dp line.
            thumb = { MiniPlayerSeekThumb(isDragging = isDragging) },
        )
    }
}

/** Resting scrub thumb for the mini-player (PRM-5): a small dot that grows while dragging. */
@Composable
private fun MiniPlayerSeekThumb(isDragging: Boolean) {
    val diameter by animateDpAsState(
        targetValue = if (isDragging) MiniThumbDraggingDiameter else MiniThumbRestingDiameter,
        animationSpec = tween(120),
        label = "miniSeekThumb",
    )
    Box(
        modifier =
            Modifier
                .size(diameter)
                .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
    )
}

private val MiniThumbRestingDiameter = 8.dp
private val MiniThumbDraggingDiameter = 14.dp
