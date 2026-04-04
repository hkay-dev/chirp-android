package dev.chirpboard.app.feature.recording.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.core.util.formatRelative
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus

@Composable
internal fun RecordingCardContent(
    item: RecordingDisplayItem,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    val recording = item.recording

    Column(modifier = modifier.fillMaxWidth()) {
        if (isProcessing) {
            Spacer(modifier = Modifier.height(10.dp))
            ProcessingIndicator(status = recording.status)
        }

        if (!isProcessing && item.summary != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight
            )
        }

        if (recording.status == RecordingStatus.FAILED) {
            Spacer(modifier = Modifier.height(8.dp))
            RecordingErrorMessage(recording.errorMessage)
        }

        if (item.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            RecordingTagsRow(tags = item.tags)
        }
    }
}

@Composable
internal fun RecordingCardMetadata(
    recording: dev.chirpboard.app.data.entity.Recording,
    profileName: String?
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = recording.createdAt.formatRelative(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        MetadataDot()

        Text(
            text = recording.durationMs.formatAsDuration(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (profileName != null) {
            MetadataDot()
            Text(
                text = profileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (recording.source != RecordingSource.APP) {
            MetadataDot()
            Text(
                text = when (recording.source) {
                    RecordingSource.KEYBOARD -> "Keyboard"
                    RecordingSource.WIDGET -> "Widget"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
internal fun ProfileIconBadge(
    profileIcon: String?,
    status: RecordingStatus
) {
    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = when (status) {
            RecordingStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
            RecordingStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = androidx.compose.animation.core.tween(
            300,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "badge_bg"
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (profileIcon != null) {
            Text(
                text = profileIcon,
                style = MaterialTheme.typography.titleMedium
            )
        } else {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when (status) {
                    RecordingStatus.COMPLETED -> MaterialTheme.colorScheme.onPrimaryContainer
                    RecordingStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
internal fun RecordingErrorMessage(errorMessage: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = errorMessage ?: "Failed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun RecordingTagsRow(tags: List<Tag>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        tags.take(4).forEach { tag ->
            SmallTagChip(tag = tag)
        }
        if (tags.size > 4) {
            Text(
                text = "+${tags.size - 4}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
internal fun SmallTagChip(tag: Tag) {
    val defaultColor = MaterialTheme.colorScheme.tertiary
    val tagColor = remember(tag.color, defaultColor) {
        tag.color?.let {
            try {
                Color(android.graphics.Color.parseColor(it))
            } catch (_: Exception) {
                defaultColor
            }
        } ?: defaultColor
    }

    Surface(
        shape = ChirpShapes.Large,
        color = tagColor.copy(alpha = 0.12f),
        modifier = Modifier.height(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tagColor)
            )
            Text(
                text = tag.name,
                style = MaterialTheme.typography.labelSmall,
                color = tagColor,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun ProcessingIndicator(status: RecordingStatus) {
    val label = when (status) {
        RecordingStatus.PENDING_TRANSCRIPTION -> "Waiting to transcribe..."
        RecordingStatus.TRANSCRIBING -> "Transcribing..."
        RecordingStatus.PENDING_ENHANCEMENT -> "Waiting to process..."
        RecordingStatus.ENHANCING -> "Processing..."
        else -> ""
    }

    val isActive = status == RecordingStatus.TRANSCRIBING || status == RecordingStatus.ENHANCING

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isActive) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        if (isActive) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(ChirpShapes.Full),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

@Composable
internal fun MetadataDot() {
    Text(
        text = "\u00B7",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
