package dev.chirpboard.app.feature.recording.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.core.util.formatRelative
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus

@Composable
fun RecordingCard(
    item: RecordingDisplayItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onRetryTranscription: (() -> Unit)? = null,
    onGenerateTitle: (() -> Unit)? = null,
    onGenerateSummary: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val recording = item.recording
    var showMenu by remember { mutableStateOf(false) }
    
    val isProcessing = recording.status in listOf(
        RecordingStatus.TRANSCRIBING,
        RecordingStatus.ENHANCING,
        RecordingStatus.PENDING_TRANSCRIPTION,
        RecordingStatus.PENDING_ENHANCEMENT
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = ChirpShapes.Medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top row: icon/profile + title + menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Profile icon or status icon
                ProfileIconBadge(
                    profileIcon = item.profileIcon,
                    status = recording.status
                )

                Spacer(modifier = Modifier.width(12.dp))
                
                // Title and metadata
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    // Metadata row: date, duration, source
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
                        
                        if (item.profileName != null) {
                            MetadataDot()
                            Text(
                                text = item.profileName,
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
                
                // Menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = {
                                showMenu = false
                                onShare()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null
                                )
                            }
                        )
                        
                        // AI options (only for completed recordings with transcript)
                        if (recording.status == RecordingStatus.COMPLETED && (onGenerateTitle != null || onGenerateSummary != null)) {
                            HorizontalDivider()
                            
                            if (onGenerateTitle != null) {
                                DropdownMenuItem(
                                    text = { Text("Generate title") },
                                    onClick = {
                                        showMenu = false
                                        onGenerateTitle()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Title,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                )
                            }
                            
                            if (onGenerateSummary != null) {
                                DropdownMenuItem(
                                    text = { Text("Generate summary") },
                                    onClick = {
                                        showMenu = false
                                        onGenerateSummary()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Summarize,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                )
                            }
                        }
                        
                        // Retry option for failed recordings
                        if (recording.status == RecordingStatus.FAILED && onRetryTranscription != null) {
                            HorizontalDivider()
                            
                            DropdownMenuItem(
                                text = { Text("Retry transcription") },
                                onClick = {
                                    showMenu = false
                                    onRetryTranscription()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                        
                        HorizontalDivider()
                        
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }

            // Processing indicator
            if (isProcessing) {
                Spacer(modifier = Modifier.height(10.dp))
                ProcessingIndicator(status = recording.status)
            }
            
            // Summary preview
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
            
            // Error message
            if (recording.status == RecordingStatus.FAILED) {
                Spacer(modifier = Modifier.height(8.dp))
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
                        text = recording.errorMessage ?: "Failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // Tag chips
            if (item.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item.tags.take(4).forEach { tag ->
                        SmallTagChip(tag = tag)
                    }
                    if (item.tags.size > 4) {
                        Text(
                            text = "+${item.tags.size - 4}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Profile emoji or status-colored mic icon.
 */
@Composable
private fun ProfileIconBadge(
    profileIcon: String?,
    status: RecordingStatus
) {
    val backgroundColor by animateColorAsState(
        targetValue = when (status) {
            RecordingStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
            RecordingStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
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

/**
 * Compact tag chip for the recording card.
 */
@Composable
private fun SmallTagChip(tag: Tag) {
    // Memoize color parsing to avoid redundant computation during list scrolling
    val defaultColor = MaterialTheme.colorScheme.tertiary
    val tagColor = remember(tag.color, defaultColor) {
        tag.color?.let {
            try { Color(android.graphics.Color.parseColor(it)) }
            catch (_: Exception) { defaultColor }
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

/**
 * Animated processing indicator with label.
 */
@Composable
private fun ProcessingIndicator(status: RecordingStatus) {
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
private fun MetadataDot() {
    Text(
        text = "\u00B7",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
