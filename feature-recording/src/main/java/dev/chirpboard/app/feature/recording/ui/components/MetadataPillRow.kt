package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Widgets
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
import dev.chirpboard.app.core.ui.R as CoreR

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataPillRow(
    durationMs: Long,
    source: RecordingSource,
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
    }
}

@Composable
private fun MetadataPill(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
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
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
            }
            Text(text = label, color = contentColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}
