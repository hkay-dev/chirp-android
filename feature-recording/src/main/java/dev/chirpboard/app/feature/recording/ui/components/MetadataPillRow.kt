package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.core.util.formatRelative
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.core.R as CoreR
import dev.chirpboard.app.feature.recording.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataPillRow(
    durationMs: Long,
    source: RecordingSource,
    status: RecordingStatus? = null,
    createdAtMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        createdAtMs?.let { createdAt ->
            MetadataPill(
                label = remember(createdAt) { java.util.Date(createdAt).formatRelative() },
                icon = Icons.Filled.Schedule,
            )
        }

        MetadataPill(
            label = durationMs.formatAsDuration(),
            icon = Icons.Filled.Timer,
        )

        MetadataPill(
            label =
                when (source) {
                    RecordingSource.APP -> stringResource(CoreR.string.rec_source_app)
                    RecordingSource.KEYBOARD -> stringResource(CoreR.string.rec_source_keyboard)
                    RecordingSource.WIDGET -> stringResource(CoreR.string.rec_source_widget)
                    RecordingSource.IMPORTED -> stringResource(CoreR.string.rec_source_imported)
                },
            icon =
                when (source) {
                    RecordingSource.APP -> Icons.Filled.PhoneAndroid
                    RecordingSource.KEYBOARD -> Icons.Filled.Keyboard
                    RecordingSource.WIDGET -> Icons.Filled.Widgets
                    RecordingSource.IMPORTED -> Icons.Filled.FileOpen
                },
        )

        status?.let { StatusChip(status = it) }
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
                    iconContent = { StatusIcon(icon = Icons.Filled.CheckCircle, tint = MaterialTheme.colorScheme.onPrimaryContainer) },
                    labelText = stringResource(R.string.rec_status_completed),
                )
            }

            RecordingStatus.FAILED -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                    iconContent = { StatusIcon(icon = Icons.Filled.ErrorOutline, tint = MaterialTheme.colorScheme.onErrorContainer) },
                    labelText = stringResource(CoreR.string.rec_status_failed),
                )
            }

            RecordingStatus.RECORDING -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { SmallProgressIndicator(color = MaterialTheme.colorScheme.onTertiaryContainer) },
                    labelText = stringResource(R.string.rec_record_button_recording),
                )
            }

            RecordingStatus.TRANSCRIBING -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { SmallProgressIndicator(color = MaterialTheme.colorScheme.onTertiaryContainer) },
                    labelText = stringResource(R.string.rec_status_transcribing_short),
                )
            }

            RecordingStatus.ENHANCING -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { SmallProgressIndicator(color = MaterialTheme.colorScheme.onTertiaryContainer) },
                    labelText = stringResource(R.string.rec_status_enhancing),
                )
            }

            RecordingStatus.PENDING_TRANSCRIPTION -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { StatusIcon(icon = Icons.Filled.Schedule, tint = MaterialTheme.colorScheme.onTertiaryContainer) },
                    labelText = stringResource(R.string.rec_status_pending),
                )
            }

            RecordingStatus.PENDING_ENHANCEMENT -> {
                StatusChipData(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    iconContent = { StatusIcon(icon = Icons.Filled.Schedule, tint = MaterialTheme.colorScheme.onTertiaryContainer) },
                    labelText = stringResource(R.string.rec_status_pending),
                )
            }
        }

    MetadataPill(
        label = labelText,
        containerColor = containerColor,
        contentColor = labelColor,
        iconContent = iconContent,
    )
}

@Composable
private fun MetadataPill(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                iconContent != null -> iconContent()
                icon != null -> StatusIcon(icon = icon, tint = contentColor)
            }
            Text(text = label, color = contentColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun StatusIcon(
    icon: ImageVector,
    tint: Color,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = tint,
    )
}

@Composable
private fun SmallProgressIndicator(color: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(16.dp),
        strokeWidth = 2.dp,
        color = color,
    )
}

private data class StatusChipData(
    val containerColor: Color,
    val labelColor: Color,
    val iconContent: @Composable () -> Unit,
    val labelText: String,
)
