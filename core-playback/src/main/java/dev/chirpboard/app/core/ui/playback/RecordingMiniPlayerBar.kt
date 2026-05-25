package dev.chirpboard.app.core.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.R
import dev.chirpboard.app.core.playback.RecordingPlaybackState
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.util.formatAsDuration

private val seekTrackEnterTransition =
    fadeIn(tween(ChirpMotion.STUDIO_REVEAL_MS, easing = FastOutSlowInEasing)) +
        expandVertically(
            animationSpec = tween(ChirpMotion.STUDIO_REVEAL_MS, easing = FastOutSlowInEasing),
        )
private val seekTrackExitTransition =
    fadeOut(tween(ChirpMotion.STUDIO_HIDE_MS, easing = FastOutSlowInEasing)) +
        shrinkVertically(
            animationSpec = tween(ChirpMotion.STUDIO_HIDE_MS, easing = FastOutSlowInEasing),
        )

@Composable
fun RecordingMiniPlayerBar(
    state: RecordingPlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
    onOpenRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .navigationBarsPadding()
                    .animateContentSize(),
        ) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                thickness = 0.5.dp,
            )

            AnimatedVisibility(
                visible = state.durationMs > 0 && state.errorMessage == null,
                enter = seekTrackEnterTransition,
                exit = seekTrackExitTransition,
            ) {
                MiniPlayerSeekTrack(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    enabled = true,
                    onSeek = onSeek,
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onPlayPause,
                    enabled = !state.isLoading,
                    modifier = Modifier.size(40.dp),
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription =
                                if (state.isPlaying) {
                                    stringResource(R.string.playback_pause)
                                } else {
                                    stringResource(R.string.playback_play)
                                },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(onClick = onOpenRecording)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = state.title.ifBlank { stringResource(R.string.playback_now_playing) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.errorMessage != null) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            text =
                                stringResource(
                                    R.string.playback_position,
                                    state.positionMs.formatAsDuration(),
                                    state.durationMs.formatAsDuration(),
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.playback_stop),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
