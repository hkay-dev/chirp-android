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
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Title
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
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.ui.tag.parseTagColor
import dev.chirpboard.app.core.ui.components.MetadataPillRow
import dev.chirpboard.app.core.ui.components.TranscriptionProgressBanner
import dev.chirpboard.app.core.ui.components.transcriptionProgressCopy
import dev.chirpboard.app.core.ui.components.transcriptionProgressKind
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.recording.rememberRecordingElapsedMs
import dev.chirpboard.app.core.ui.haptics.ChirpHaptics
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.core.ui.theme.chirpAccents
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
    // AUD-02/AUD-05/ERR-14: live-session advisory rendered as the live banner's hint line;
    // only the live-capture row ever consumes it.
    liveCaptureAdvisory: RecordingSessionAdvisory? = null,
) {
    val isCurrentItem = playbackState.recordingId == item.id
    val isPlayingCurrent = isCurrentItem && playbackState.isPlaying
    // State, never a plain Long: the elapsed value is handed to a leaf pill row so the 1 Hz tick
    // cannot invalidate this row's title, buttons and reveals for the whole capture.
    val liveCaptureElapsedMs =
        if (item.isLiveCapture) {
            rememberRecordingElapsedMs(recordingState)
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
                    onLongClickLabel = stringResource(R.string.rec_more_actions),
                    onLongClick = onLongClick,
                ).padding(horizontal = ChirpSpacing.ScreenHorizontal, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
        ) {
            Text(
                text = item.title,
                // One step up from titleMedium/Medium: the row title (date or processed title) is
                // the anchor of each list row and read under-weighted at 16sp Medium.
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!item.isLiveCapture) {
                if (item.isAudioReady) {
                    // A11Y-6: no fixed 40dp size on either button — the M3 default keeps the 40dp
                    // visual container while restoring 48dp interactive bounds; these two controls
                    // sit 4-8dp apart, so fixed 40dp nodes got no touch-bounds expansion between
                    // each other.
                    FilledTonalIconButton(
                        onClick = onPlayClick,
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                    ) {
                        Icon(
                            imageVector = if (isPlayingCurrent) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription =
                                if (isPlayingCurrent) {
                                    stringResource(R.string.desc_pause)
                                } else {
                                    stringResource(R.string.desc_play)
                                },
                        )
                    }
                }
                // PRM-4: a visible, discoverable overflow affordance. Premium lists expose row
                // actions (share/delete/…) without forcing the user to guess at a long-press; this
                // kebab opens the SAME actions sheet that long-press does (long-press stays as the
                // secondary path). The button has its own click target so it does not trigger the
                // row's onClick (open studio).
                IconButton(onClick = onLongClick) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.rec_more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
        ) {
            if (liveCaptureElapsedMs != null) {
                LiveCaptureMetadataPillRow(
                    createdAtMs = item.createdAtMs,
                    elapsedMs = liveCaptureElapsedMs,
                    source = item.source,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                MetadataPillRow(
                    createdAtMs = item.createdAtMs,
                    durationMs = item.durationMs,
                    source = item.source,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // NOTES: a quiet glyph marks rows that carry a user note, so a described recording
            // is findable at a glance without adding another pill to the metadata row.
            if (!item.recording.notes.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.StickyNote2,
                    contentDescription = stringResource(R.string.desc_has_note),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Status-driven sections keep PushDownReveal: their visibility can flip while the row is
        // on screen (a live capture stops, transcription progresses), so the height change is
        // worth animating.
        PushDownReveal(visible = item.isLiveCapture) {
            LiveCaptureHomeBanner(
                isPaused = isLiveCapturePaused,
                advisory = liveCaptureAdvisory,
            )
        }

        val transcriptionProgressCopy = if (item.isLiveCapture) null else item.status.transcriptionProgressCopy()
        PushDownReveal(visible = transcriptionProgressCopy != null) {
            transcriptionProgressCopy?.let { copy ->
                TranscriptionProgressBanner(
                    copy = copy,
                    kind = item.status.transcriptionProgressKind(),
                )
            }
        }

        PushDownReveal(visible = shouldShowStuckRecoveryAction(item.status)) {
            Text(
                text =
                    homeProcessingNoteRes(item.errorMessage)?.let { stringResource(it) }
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

        // PLH-4: a deliberately skipped recording (profile Auto Transcribe off / user cancel)
        // shows why there is no transcript yet; the actions sheet offers "Transcribe".
        PushDownReveal(visible = item.status == RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION) {
            Text(
                text = stringResource(R.string.rec_awaiting_transcription_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Summary and tags are immutable while the row is visible (they are only produced by a
        // status transition that re-keys the item), so a plain `if` avoids instantiating a
        // Transition state machine per row in the scroll hot path. The Column's
        // animatePushDownLayout() still animates the height change if they appear in place.
        item.summary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (item.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.tags.take(MAX_VISIBLE_TAGS).forEach { tag ->
                    CompactTagChip(name = tag.name, colorHex = tag.color)
                }
                if (item.tags.size > MAX_VISIBLE_TAGS) {
                    Text(
                        text = "+${item.tags.size - MAX_VISIBLE_TAGS}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Maximum number of tag chips rendered inline on a recording row before collapsing to a count. */
private const val MAX_VISIBLE_TAGS = 3

/**
 * Metadata pills for a live-capture row, with the ticking duration.
 *
 * A separate composable purely for invalidation scope: [elapsedMs] is read here, so the per-second
 * tick recomposes only the pills. Read in the row itself it re-executed the title, action buttons
 * and every PushDownReveal once a second for the entire capture.
 */
@Composable
private fun LiveCaptureMetadataPillRow(
    createdAtMs: Long,
    elapsedMs: State<Long>,
    source: RecordingSource,
    modifier: Modifier = Modifier,
) {
    MetadataPillRow(
        createdAtMs = createdAtMs,
        durationMs = elapsedMs.value,
        source = source,
        modifier = modifier,
    )
}

@Composable
private fun LiveCaptureHomeBanner(
    isPaused: Boolean,
    advisory: RecordingSessionAdvisory? = null,
) {
    // Keep the pulse as State<Float> and read it inside drawBehind so the infinite transition
    // invalidates only the draw phase. Reading `.value` here in composition would re-run the
    // whole banner scope every vsync for the entire live capture.
    val pulseAlpha =
        if (isPaused) {
            null
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
            )
        }
    // PRM-2 / DECISIONS: the "live" row uses the shared recording accent, not raw Material error-red,
    // so the home live indicator is cohesive with the keyboard glow, the record screen and the dialog.
    val accents = MaterialTheme.colorScheme.chirpAccents
    val dotColor = accents.recordingLive

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accents.recordingLiveContainer.copy(alpha = 0.45f),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ChirpSpacing.Medium, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .drawBehind {
                            val alpha = pulseAlpha?.value ?: 1f
                            drawCircle(color = dotColor.copy(alpha = alpha))
                        },
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.rec_live_capture_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = accents.recordingLive,
                )
                // AUD-02/AUD-05/ERR-14: when the service reports a session advisory (focus
                // pause, silenced mic, low storage), it replaces the navigation hint so the
                // live row explains the condition. The polite live region announces the
                // change for TalkBack without stealing focus.
                Text(
                    text =
                        stringResource(
                            advisory?.advisoryStringRes()
                                ?: if (isPaused) {
                                    R.string.rec_live_capture_banner_subtitle_paused
                                } else {
                                    R.string.rec_live_capture_banner_subtitle_active
                                },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
            colorHex?.let { parseTagColor(it, defaultColor) } ?: defaultColor
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
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Bottom sheet with recording actions.
 */
@Composable
internal fun RecordingActionsSheet(
    item: RecordingDisplayItem,
    onShare: (() -> Unit)?,
    onDelete: () -> Unit,
    onRetryTranscription: (() -> Unit)?,
    onGenerateTitle: (() -> Unit)?,
    onGenerateSummary: (() -> Unit)?,
    onRecoverStuck: (() -> Unit)?,
    onCancelTranscription: (() -> Unit)? = null,
    onTranscribeNow: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = ChirpSpacing.ExtraExtraLarge),
    ) {
        // Header
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier =
                Modifier.padding(
                    horizontal = ChirpSpacing.ExtraLarge,
                    vertical = ChirpSpacing.Large,
                ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        HorizontalDivider()

        if (onShare != null) {
            SheetActionItem(
                icon = Icons.Rounded.Share,
                text = stringResource(CoreR.string.rec_share),
                onClick = onShare,
            )
        }

        // AI Options (for completed recordings)
        if (onGenerateTitle != null || onGenerateSummary != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = ChirpSpacing.ExtraLarge))

            if (onGenerateTitle != null) {
                SheetActionItem(
                    icon = Icons.Rounded.Title,
                    text = stringResource(R.string.rec_gen_title),
                    onClick = onGenerateTitle,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (onGenerateSummary != null) {
                SheetActionItem(
                    icon = Icons.Rounded.Summarize,
                    text = stringResource(R.string.rec_gen_summary),
                    onClick = onGenerateSummary,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        // Retry (for failed recordings)
        if (onRetryTranscription != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = ChirpSpacing.ExtraLarge))
            SheetActionItem(
                icon = Icons.Rounded.Refresh,
                text = stringResource(CoreR.string.rec_retry_transcription),
                onClick = onRetryTranscription,
            )
        }

        // PLH-4: explicit start for a recording whose profile skipped auto-transcription.
        if (onTranscribeNow != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = ChirpSpacing.ExtraLarge))
            SheetActionItem(
                icon = Icons.Rounded.Refresh,
                text = stringResource(R.string.rec_transcribe_now),
                onClick = onTranscribeNow,
            )
        }

        // PIPE-07: cancel a queued/running transcription.
        if (onCancelTranscription != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = ChirpSpacing.ExtraLarge))
            SheetActionItem(
                icon = Icons.Rounded.Close,
                text = stringResource(CoreR.string.rec_cancel_transcription),
                onClick = onCancelTranscription,
            )
        }

        if (onRecoverStuck != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = ChirpSpacing.ExtraLarge))
            SheetActionItem(
                icon = Icons.Rounded.Refresh,
                text = stringResource(R.string.rec_recover_stuck_processing),
                onClick = onRecoverStuck,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = ChirpSpacing.ExtraLarge))

        // Delete
        SheetActionItem(
            icon = Icons.Rounded.Delete,
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
                .padding(horizontal = ChirpSpacing.ExtraLarge, vertical = ChirpSpacing.Large),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Large),
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
                                imageVector = Icons.Rounded.Person,
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
                // PRM-1: a confirming tick on the primary Record CTA. Fired only when the action is
                // actually enabled so a tap during the entry-check gives no false feedback.
                ChirpHaptics.tap(context)
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
                    imageVector = Icons.Rounded.Mic,
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
            icon = Icons.Rounded.Mic,
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
                        .padding(
                            horizontal = ChirpSpacing.ScreenHorizontal,
                            vertical = ChirpSpacing.Small,
                        ),
            )
        }
    }
}
