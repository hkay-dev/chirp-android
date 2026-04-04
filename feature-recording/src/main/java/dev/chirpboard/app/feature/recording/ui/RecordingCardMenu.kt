package dev.chirpboard.app.feature.recording.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.chirpboard.app.data.model.RecordingStatus

@Composable
internal fun RecordingCardMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    recordingStatus: RecordingStatus,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetryTranscription: (() -> Unit)? = null,
    onGenerateTitle: (() -> Unit)? = null,
    onGenerateSummary: (() -> Unit)? = null
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            text = { Text("Share") },
            onClick = {
                onDismissRequest()
                onShare()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null
                )
            }
        )

        if (recordingStatus == RecordingStatus.COMPLETED &&
            (onGenerateTitle != null || onGenerateSummary != null)
        ) {
            HorizontalDivider()

            if (onGenerateTitle != null) {
                DropdownMenuItem(
                    text = { Text("Generate title") },
                    onClick = {
                        onDismissRequest()
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
                        onDismissRequest()
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

        if (recordingStatus == RecordingStatus.FAILED && onRetryTranscription != null) {
            HorizontalDivider()

            DropdownMenuItem(
                text = { Text("Retry transcription") },
                onClick = {
                    onDismissRequest()
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
                onDismissRequest()
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
