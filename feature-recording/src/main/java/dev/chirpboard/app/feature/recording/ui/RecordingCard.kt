package dev.chirpboard.app.feature.recording.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.theme.ChirpShapes
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
                    RecordingCardMetadata(
                        recording = recording,
                        profileName = item.profileName
                    )
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
                    
                    RecordingCardMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        recordingStatus = recording.status,
                        onShare = onShare,
                        onDelete = onDelete,
                        onRetryTranscription = onRetryTranscription,
                        onGenerateTitle = onGenerateTitle,
                        onGenerateSummary = onGenerateSummary
                    )
                }
            }

            RecordingCardContent(
                item = item,
                isProcessing = isProcessing
            )
        }
    }
}
