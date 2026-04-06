package dev.chirpboard.app.feature.recording.ui.studio.tabs

import dev.chirpboard.app.data.model.RecordingStatus
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SummaryTab(
    summaryMarkdown: String,
    status: RecordingStatus?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    if (status == RecordingStatus.PENDING_TRANSCRIPTION || status == RecordingStatus.TRANSCRIBING || status == RecordingStatus.ENHANCING || status == RecordingStatus.PENDING_ENHANCEMENT) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (status == RecordingStatus.ENHANCING || status == RecordingStatus.PENDING_ENHANCEMENT) "Enhancing summary..." else "Transcribing audio...",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        return
    }

    if (summaryMarkdown.isEmpty() && status == RecordingStatus.COMPLETED) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "No summary available.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Text(
                text = summaryMarkdown,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
