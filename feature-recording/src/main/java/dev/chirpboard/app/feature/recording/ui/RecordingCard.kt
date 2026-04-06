package dev.chirpboard.app.feature.recording.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
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
    modifier: Modifier = Modifier,
) {
    val recording = item.recording
    val isProcessing =
        recording.status in
            listOf(
                RecordingStatus.TRANSCRIBING,
                RecordingStatus.ENHANCING,
                RecordingStatus.PENDING_TRANSCRIPTION,
                RecordingStatus.PENDING_ENHANCEMENT,
            )

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = ChirpShapes.Medium,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            RecordingCardHeader(
                item = item,
                onDelete = onDelete,
                onShare = onShare,
                onRetryTranscription = onRetryTranscription,
                onGenerateTitle = onGenerateTitle,
                onGenerateSummary = onGenerateSummary,
            )

            RecordingCardContent(
                item = item,
                isProcessing = isProcessing,
            )
        }
    }
}
