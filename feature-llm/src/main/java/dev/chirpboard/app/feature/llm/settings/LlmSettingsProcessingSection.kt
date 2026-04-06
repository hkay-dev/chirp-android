package dev.chirpboard.app.feature.llm.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics

@Composable
internal fun LlmSettingsProcessingSection(
    uiState: LlmSettingsViewModel.UiState,
    onSetAutoTitle: (Boolean) -> Unit,
    onSetAutoSummary: (Boolean) -> Unit
) {
    Spacer(Modifier.padding(vertical = 8.dp))
    HorizontalDivider()
    Spacer(Modifier.padding(vertical = 8.dp))

    Text(
        text = "Automatic Processing",
        style = MaterialTheme.typography.titleMedium
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .clickable { onSetAutoTitle(!uiState.autoTitle) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-generate titles",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Automatically create descriptive titles after transcription",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = uiState.autoTitle,
                onCheckedChange = null
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .clickable { onSetAutoSummary(!uiState.autoSummary) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-generate summaries",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Automatically create brief summaries for the home screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = uiState.autoSummary,
                onCheckedChange = null
            )
        }
    }
}
