package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.chirpboard.app.core.ui.R
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.core.util.formatAsDuration

/**
 * Whether the processing pill should render in its highlighted (tertiary accent) state.
 *
 * Active when the processing list filter is on, or when there is at least one recording currently
 * processing. Extracted as a pure function so the pill-state logic is unit-testable.
 */
internal fun processingPillHighlighted(
    processingCount: Int,
    processingFilterActive: Boolean,
): Boolean = processingFilterActive || processingCount > 0

/**
 * Aggregate home-screen stat pills: recording count, total duration, and processing filter.
 *
 * For per-recording metadata (date, duration, source), use [MetadataPillRow] instead. Both rows
 * render the shared [ChirpPill] capsule, so the count/duration/processing pills now match the
 * per-recording metadata pills exactly (filled, fully-rounded, no border) — resolving the
 * outlined-vs-filled mismatch (VIS-1 / VIS-3). The count and duration pills are display-only (no
 * dead ripple); only the processing pill is interactive.
 *
 * @param recordingCount Total number of recordings
 * @param totalDurationMs Total duration of all recordings in milliseconds
 * @param processingCount Number of recordings currently being processed
 * @param onProcessingClick Callback when the processing pill is clicked
 * @param processingFilterActive Whether the processing list filter is currently active
 * @param modifier Optional modifier for customization
 */
@Composable
fun StatsPillRow(
    recordingCount: Int,
    totalDurationMs: Long,
    processingCount: Int,
    onProcessingClick: () -> Unit,
    processingFilterActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
        contentPadding = PaddingValues(horizontal = ChirpSpacing.ScreenHorizontal),
        modifier = modifier,
    ) {
        // Recording count pill (display-only)
        item {
            ChirpPill(
                label = recordingCount.toString(),
                icon = Icons.Filled.AudioFile,
                contentDescription = stringResource(R.string.desc_recordings),
            )
        }

        // Total duration pill (display-only)
        item {
            ChirpPill(
                label = totalDurationMs.formatAsDuration(),
                icon = Icons.Filled.Schedule,
                contentDescription = stringResource(R.string.desc_total_duration),
            )
        }

        // Processing count pill (interactive; pulse animation when > 0)
        item {
            val isProcessing = processingCount > 0
            // Keep the animation as State and read .value inside graphicsLayer so the pulse
            // invalidates only the draw/layer phase, not this pill's composition each vsync.
            // Reduced-motion: the pulse is decorative (the count + tint carry the meaning),
            // so skip it entirely when animations are disabled system-wide.
            val pulseAlpha =
                if (isProcessing && !reducedMotionEnabled()) {
                    val infiniteTransition = rememberInfiniteTransition(label = "processing_pulse")
                    infiniteTransition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 1f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(durationMillis = 800),
                                repeatMode = RepeatMode.Reverse,
                            ),
                        label = "pulse_alpha",
                    )
                } else {
                    null
                }
            val showActiveFilter = processingPillHighlighted(processingCount, processingFilterActive)
            // A11Y: the pill is a filter toggle whose active state was only a color change;
            // expose selected + a state description so TalkBack announces on/off.
            val filterStateDescription =
                if (processingFilterActive) {
                    stringResource(R.string.desc_processing_filter_on)
                } else {
                    stringResource(R.string.desc_processing_filter_off)
                }
            ChirpPill(
                label = processingCount.toString(),
                icon = Icons.Filled.Sync,
                onClick = onProcessingClick,
                containerColor =
                    if (showActiveFilter) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                contentColor =
                    if (showActiveFilter) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                contentDescription = stringResource(R.string.desc_processing),
                modifier =
                    (
                        if (pulseAlpha != null) {
                            Modifier.graphicsLayer { alpha = pulseAlpha.value }
                        } else {
                            Modifier
                        }
                    ).semantics {
                        selected = processingFilterActive
                        stateDescription = filterStateDescription
                    },
            )
        }
    }
}
