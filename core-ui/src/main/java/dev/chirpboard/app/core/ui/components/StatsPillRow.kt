package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.core.ui.R
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.util.formatAsDuration

/**
 * Aggregate home-screen stat pills: recording count, total duration, and processing filter.
 *
 * For per-recording metadata (date, duration, source), use [MetadataPillRow] instead.
 *
 * @param recordingCount Total number of recordings
 * @param totalDurationMs Total duration of all recordings in milliseconds
 * @param processingCount Number of recordings currently being processed
 * @param onProcessingClick Callback when the processing pill is clicked
 * @param modifier Optional modifier for customization
 */
@Composable
fun StatsPillRow(
    recordingCount: Int,
    totalDurationMs: Long,
    processingCount: Int,
    onProcessingClick: () -> Unit,
    modifier: Modifier = Modifier
) {

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier
    ) {
        // Recording count pill
        item {
            SuggestionChip(
                onClick = {},
                label = { Text(recordingCount.toString()) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.AudioFile,
                        contentDescription = stringResource(R.string.desc_recordings),
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    iconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }

        // Total duration pill
        item {
            SuggestionChip(
                onClick = {},
                label = { Text(totalDurationMs.formatAsDuration()) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = stringResource(R.string.desc_total_duration),
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    iconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }

        // Processing count pill (with pulse animation when > 0)
        item {
            val isProcessing = processingCount > 0
            val pulseAlpha = if (isProcessing) {
                val infiniteTransition = rememberInfiniteTransition(label = "processing_pulse")
                infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 800),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse_alpha"
                ).value
            } else {
                1f
            }
            SuggestionChip(
                onClick = onProcessingClick,
                label = { Text(processingCount.toString()) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = stringResource(R.string.desc_processing),
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = if (isProcessing) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    labelColor = if (isProcessing) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    iconContentColor = if (isProcessing) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                ),
                modifier = if (isProcessing) Modifier.alpha(pulseAlpha) else Modifier
            )
        }
    }
}
