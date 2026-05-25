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
import dev.chirpboard.app.core.R as CoreR
import dev.chirpboard.app.feature.recording.R
import androidx.compose.ui.res.stringResource

@Composable
internal fun RecordingCardMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    recordingStatus: RecordingStatus,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetryTranscription: (() -> Unit)? = null,
    onGenerateTitle: (() -> Unit)? = null,
    onGenerateSummary: (() -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(CoreR.string.rec_share)) },
            onClick = {
                onDismissRequest()
                onShare()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                )
            },
        )

        if (recordingStatus == RecordingStatus.COMPLETED &&
            (onGenerateTitle != null || onGenerateSummary != null)
        ) {
            HorizontalDivider()

            if (onGenerateTitle != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rec_gen_title)) },
                    onClick = {
                        onDismissRequest()
                        onGenerateTitle()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Title,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    },
                )
            }

            if (onGenerateSummary != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rec_gen_summary)) },
                    onClick = {
                        onDismissRequest()
                        onGenerateSummary()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Summarize,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    },
                )
            }
        }

        if (recordingStatus == RecordingStatus.FAILED && onRetryTranscription != null) {
            HorizontalDivider()

            DropdownMenuItem(
                text = { Text(stringResource(R.string.rec_retry_transcription)) },
                onClick = {
                    onDismissRequest()
                    onRetryTranscription()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                    )
                },
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(CoreR.string.rec_delete)) },
            onClick = {
                onDismissRequest()
                onDelete()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
        )
    }
}
