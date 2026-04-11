package dev.chirpboard.app.feature.recording.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.feature.recording.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RecordingCardHeader(
    item: RecordingDisplayItem,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onRetryTranscription: (() -> Unit)?,
    onGenerateTitle: (() -> Unit)?,
    onGenerateSummary: (() -> Unit)?,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileIconBadge(
            profileIcon = item.profileIcon,
            status = item.status,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(2.dp))

            RecordingCardMetadata(
                createdAtMs = item.createdAtMs,
                durationMs = item.durationMs,
                source = item.source,
                profileName = item.profileName,
            )
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.desc_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RecordingCardMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                recordingStatus = item.status,
                onShare = onShare,
                onDelete = onDelete,
                onRetryTranscription = onRetryTranscription,
                onGenerateTitle = onGenerateTitle,
                onGenerateSummary = onGenerateSummary,
            )
        }
    }
}
