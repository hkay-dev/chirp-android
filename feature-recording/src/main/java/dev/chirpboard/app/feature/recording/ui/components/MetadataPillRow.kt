package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataPillRow(
    durationMs: Long,
    source: RecordingSource,
    status: RecordingStatus,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        // Duration pill
        SuggestionChip(
            onClick = {},
            label = { Text(durationMs.formatAsDuration()) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )

        // Source pill
        SuggestionChip(
            onClick = {},
            label = {
                Text(
                    when (source) {
                        RecordingSource.APP -> "App"
                        RecordingSource.KEYBOARD -> "Keyboard"
                        RecordingSource.WIDGET -> "Widget"
                    },
                )
            },
            icon = {
                Icon(
                    imageVector =
                        when (source) {
                            RecordingSource.APP -> Icons.Filled.PhoneAndroid
                            RecordingSource.KEYBOARD -> Icons.Filled.Keyboard
                            RecordingSource.WIDGET -> Icons.Filled.Widgets
                        },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )

        // Status pill
        StatusChip(status = status)
    }
}

@Composable
private fun StatusChip(status: RecordingStatus) {
    val (containerColor, labelColor, iconContent, labelText) =
        when (status) {
            RecordingStatus.COMPLETED -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    iconContent = { StatusIcon(Icons.Filled.CheckCircle) },
                    labelText = "Completed",
                )
            }

            RecordingStatus.FAILED -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                    iconContent = { StatusIcon(Icons.Filled.ErrorOutline) },
                    labelText = "Failed",
                )
            }

            RecordingStatus.RECORDING -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { SmallProgressIndicator() },
                    labelText = "Recording",
                )
            }

            RecordingStatus.TRANSCRIBING -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { SmallProgressIndicator() },
                    labelText = "Transcribing",
                )
            }

            RecordingStatus.ENHANCING -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { SmallProgressIndicator() },
                    labelText = "Enhancing",
                )
            }

            RecordingStatus.PENDING_TRANSCRIPTION -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { StatusIcon(Icons.Filled.Schedule) },
                    labelText = "Pending",
                )
            }

            RecordingStatus.PENDING_ENHANCEMENT -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { StatusIcon(Icons.Filled.Schedule) },
                    labelText = "Pending",
                )
            }
        }

    SuggestionChip(
        onClick = {},
        label = { Text(labelText, color = labelColor) },
        icon = iconContent,
        colors =
            SuggestionChipDefaults.suggestionChipColors(
                containerColor = containerColor,
                labelColor = labelColor,
                iconContentColor = labelColor,
            ),
    )
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun SmallProgressIndicator() {
    CircularProgressIndicator(
        modifier = Modifier.size(16.dp),
        strokeWidth = 2.dp,
    )
}

private data class StatusChipData(
    val containerColor: Color,
    val labelColor: Color,
    val iconContent: @Composable () -> Unit,
    val labelText: String,
)
