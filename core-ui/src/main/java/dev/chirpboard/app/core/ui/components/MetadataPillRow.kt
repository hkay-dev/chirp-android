package dev.chirpboard.app.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.core.util.formatRelative
import dev.chirpboard.app.data.model.RecordingSource
import java.util.Date

/**
 * Per-recording metadata pills: relative date, duration, and source.
 *
 * Complements [StatsPillRow], which shows aggregate home-screen stats (count, total duration,
 * processing filter). Use this component for individual recording surfaces such as the home list
 * and studio header.
 */
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
                label = remember(createdAt) { Date(createdAt).formatRelative() },
                icon = Icons.Filled.Schedule,
            )
        }

        MetadataPill(
            label = durationMs.formatAsDuration(),
            icon = Icons.Filled.Timer,
        )

        MetadataPill(
            label = source.label(),
            icon = source.icon(),
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
