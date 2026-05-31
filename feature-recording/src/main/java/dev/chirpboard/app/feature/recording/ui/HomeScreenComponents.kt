package dev.chirpboard.app.feature.recording.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import dev.chirpboard.app.core.ui.components.ChirpPrimaryExtendedFab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.core.ui.components.MetadataPillRow
import dev.chirpboard.app.core.ui.components.TranscriptionProgressBanner
import dev.chirpboard.app.core.ui.components.transcriptionProgressCopy
import dev.chirpboard.app.core.ui.components.transcriptionProgressKind
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Individual recording list item - no card wrapper.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun RecordingListItem(
    item: RecordingDisplayItem,
    playbackState: RecordingPlaybackRowState,
    recordingState: RecordingState,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCurrentItem = playbackState.recordingId == item.id
    val isPlayingCurrent = isCurrentItem && playbackState.isPlaying
    val liveCaptureElapsedMs =
        if (item.isLiveCapture) {
            rememberLiveCaptureElapsedMs(recordingState)
        } else {
            null
        }
    val isLiveCapturePaused = item.isLiveCapture && recordingState is RecordingState.Paused

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .animatePushDownLayout()
                .semantics(mergeDescendants = true) {}
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!item.isLiveCapture) {
                FilledTonalIconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(40.dp),
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                ) {
                    Icon(
                        imageVector = if (isPlayingCurrent) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription =
                            if (isPlayingCurrent) {
                                stringResource(R.string.desc_pause)
                            } else {
                                stringResource(R.string.desc_play)
                            },
                    )
                }
            }
        }

        MetadataPillRow(
            createdAtMs = item.createdAtMs,
            durationMs = liveCaptureElapsedMs ?: item.durationMs,
            source = item.source,
        )

        PushDownReveal(visible = item.isLiveCapture) {
            LiveCaptureHomeBanner(isPaused = isLiveCapturePaused)
        }

        PushDownReveal(visible = !item.isLiveCapture && item.status.transcriptionProgressCopy() != null) {
            item.status.transcriptionProgressCopy()?.let { copy ->
                TranscriptionProgressBanner(
                    copy = copy,
                    kind = item.status.transcriptionProgressKind(),
                )
            }
        }

        PushDownReveal(visible = item.summary != null) {
            item.summary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        PushDownReveal(visible = shouldShowStuckRecoveryAction(item.status)) {
            Text(
                text =
                    item.errorMessage
                        ?: stringResource(
                            R.string.rec_stuck_recovery_message,
                            item.status.name
                                .lowercase()
                                .replace('_', ' '),
                        ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        PushDownReveal(visible = item.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.tags.take(3).forEach { tag ->
                    CompactTagChip(name = tag.name, colorHex = tag.color)
                }
                if (item.tags.size > 3) {
                    Text(
                        text = "+${item.tags.size - 3}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberLiveCaptureElapsedMs(recordingState: RecordingState): Long {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var previousSegmentsMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is RecordingState.Starting -> {
                previousSegmentsMs = 0L
                elapsedMs = 0L
            }

            is RecordingState.Recording -> {
                val segmentStart = state.startTimeMs
                while (true) {
                    elapsedMs = previousSegmentsMs + (System.currentTimeMillis() - segmentStart)
                    delay(ChirpMotion.TIMER_TICK_MS)
                }
            }

            is RecordingState.Paused -> {
                previousSegmentsMs = state.accumulatedMs
                elapsedMs = state.accumulatedMs
            }

            else -> Unit
        }
    }

    return elapsedMs
}

@Composable
private fun LiveCaptureHomeBanner(isPaused: Boolean) {
    val pulseAlpha =
        if (isPaused) {
            1f
        } else {
            val infiniteTransition = rememberInfiniteTransition(label = "live_capture_pulse")
            infiniteTransition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "live_capture_pulse_alpha",
            ).value
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha)),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.rec_live_capture_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text =
                        stringResource(
                            if (isPaused) {
                                R.string.rec_live_capture_banner_subtitle_paused
                            } else {
                                R.string.rec_live_capture_banner_subtitle_active
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Compact tag chip for list items.
 */
@Composable
private fun CompactTagChip(
    name: String,
    colorHex: String?,
) {
    // Memoize color parsing to avoid redundant computation during list scrolling
    // Color.parseColor is expensive and list items recompose frequently during scroll
    val defaultColor = MaterialTheme.colorScheme.tertiary
    val tagColor =
        remember(colorHex, defaultColor) {
            colorHex?.let {
                try {
                    Color(android.graphics.Color.parseColor(it))
                } catch (_: Exception) {
                    defaultColor
                }
            } ?: defaultColor
        }

    Row(
        modifier =
            Modifier
                .background(
                    color = tagColor.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tagColor),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = tagColor,
            maxLines = 1,
        )
    }
}

/**
 * Bottom sheet with recording actions.
 */
@Composable
internal fun RecordingActionsSheet(
    item: RecordingDisplayItem,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetryTranscription: (() -> Unit)?,
    onGenerateTitle: (() -> Unit)?,
    onGenerateSummary: (() -> Unit)?,
    onRecoverStuck: (() -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
    ) {
        // Header
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        HorizontalDivider()

        // Share
        SheetActionItem(
            icon = Icons.Default.Share,
            text = stringResource(CoreR.string.rec_share),
            onClick = onShare,
        )

        // AI Options (for completed recordings)
        if (onGenerateTitle != null || onGenerateSummary != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))

            if (onGenerateTitle != null) {
                SheetActionItem(
                    icon = Icons.Default.Title,
                    text = stringResource(R.string.rec_gen_title),
                    onClick = onGenerateTitle,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (onGenerateSummary != null) {
                SheetActionItem(
                    icon = Icons.Default.Summarize,
                    text = stringResource(R.string.rec_gen_summary),
                    onClick = onGenerateSummary,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        // Retry (for failed recordings)
        if (onRetryTranscription != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
            SheetActionItem(
                icon = Icons.Default.Refresh,
                text = stringResource(R.string.rec_retry_transcription),
                onClick = onRetryTranscription,
            )
        }

        if (onRecoverStuck != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
            SheetActionItem(
                icon = Icons.Default.Refresh,
                text = stringResource(R.string.rec_recover_stuck_processing),
                onClick = onRecoverStuck,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))

        // Delete
        SheetActionItem(
            icon = Icons.Default.Delete,
            text = stringResource(CoreR.string.rec_delete),
            onClick = onDelete,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .combinedClickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeQuickStartSurface(
    quickStarts: List<HomeQuickStartEntry>,
    onQuickStartClick: (UUID) -> Unit,
    isRecordEntryChecking: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(HomeScreenRecordEntryTestTags.QuickStartSurface),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.rec_home_quick_starts_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickStarts.forEach { quickStart ->
                    OutlinedButton(
                        onClick = { onQuickStartClick(quickStart.id) },
                        enabled = isRecordEntryActionEnabled(isRecordEntryChecking),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag(quickStartTestTag(quickStart.id)),
                    ) {
                        val icon = quickStart.icon
                        if (!icon.isNullOrBlank()) {
                            Text(
                                text = icon,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = quickStart.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Extended FAB with breathing animation.
 */
@Composable
fun BreathingExtendedFab(
    expanded: Boolean,
    isChecking: Boolean,
    onClick: () -> Unit,
    isScrollInProgress: Boolean = false,
) {
    val scaleAnimation =
        if (!isChecking && expanded && !isScrollInProgress) {
            val infiniteTransition = rememberInfiniteTransition(label = "breathing")
            infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.03f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "fab_scale",
            )
        } else {
            null
        }

    ChirpPrimaryExtendedFab(
        onClick = {
            if (isRecordEntryActionEnabled(isChecking)) {
                onClick()
            }
        },
        expanded = expanded,
        modifier =
            Modifier
                .graphicsLayer {
                    val scale = scaleAnimation?.value ?: 1f
                    scaleX = scale
                    scaleY = scale
                }.testTag(HomeScreenRecordEntryTestTags.RecordFab),
        icon = {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                )
            }
        },
        text = {
            Text(recordFabLabel(isChecking))
        },
    )
}

/**
 * Animated empty state with floating mic icon.
 */
@Composable
fun AnimatedEmptyState(
    onRecordClick: () -> Unit,
    onQuickStartClick: (UUID) -> Unit,
    quickStarts: List<HomeQuickStartEntry>,
    isRecordEntryChecking: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Default.Mic,
            title = stringResource(R.string.rec_empty_state_title),
            description = stringResource(R.string.rec_empty_state_subtitle),
            animateIcon = true,
            modifier = Modifier.weight(1f),
        )

        if (shouldShowHomeQuickStartSurface(quickStarts)) {
            HomeQuickStartSurface(
                quickStarts = quickStarts,
                onQuickStartClick = onQuickStartClick,
                isRecordEntryChecking = isRecordEntryChecking,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
