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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import dev.chirpboard.app.core.playback.R as PlaybackR
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

/** Lift the persistent now-playing bar off scrolling content with a subtle shadow (INS-5). */
private val MiniPlayerElevation = 3.dp

@Composable
fun RecordingMiniPlayerBar(
    state: RecordingPlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
    onOpenRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seekTrackVisible = state.durationMs > 0 && state.errorMessage == null
    Surface(
        modifier = modifier.fillMaxWidth(),
        // INS-5 / PRM-6: a tonally-distinct container plus a small shadow so the bar reads as a
        // floating transport over the list rather than merging into the same `surface` background.
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = MiniPlayerElevation,
    ) {
        Column(
            modifier =
                Modifier
                    .navigationBarsPadding()
                    .animateContentSize(),
        ) {
            // INS-6: when the seek track is shown it already delineates the bar's top edge, so the
            // hairline divider stacked directly above it read as an unintentional double border.
            // Drop the divider whenever the seek track is visible; the shadow provides separation.
            if (!seekTrackVisible) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    thickness = 0.5.dp,
                )
            }

            AnimatedVisibility(
                visible = seekTrackVisible,
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
                // No fixed 40dp size on the buttons: the M3 default keeps the 40dp
                // visual container while restoring 48dp interactive bounds; these two
                // controls sit 4dp from their neighbors, so fixed 40dp nodes got no
                // touch-bounds expansion between each other (a11y touch-target audit).
                FilledTonalIconButton(
                    onClick = onPlayPause,
                    enabled = !state.isLoading,
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
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
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
                            .clickable(
                                onClickLabel = stringResource(PlaybackR.string.playback_open_recording),
                                onClick = onOpenRecording,
                            )
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
                    } else if (state.noticeMessage != null) {
                        Text(
                            text = state.noticeMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            // PRM-8: tabular figures so the advancing position does not jitter width.
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontFeatureSettings = "tnum",
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.playback_stop),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
