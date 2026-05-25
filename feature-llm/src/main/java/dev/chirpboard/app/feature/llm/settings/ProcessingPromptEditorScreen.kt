package dev.chirpboard.app.feature.llm.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.ChirpLeafScaffold
import dev.chirpboard.app.feature.llm.R

@Composable
fun ProcessingPromptEditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProcessingPromptEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ChirpLeafScaffold(
        title =
            when {
                uiState.isNewPreset -> stringResource(R.string.llm_prompt_editor_new_title)
                else -> uiState.name.ifBlank { stringResource(R.string.llm_prompt_editor_title) }
            },
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.statusMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (uiState.canEditName) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = uiState.name,
                    onValueChange = viewModel::updateName,
                    label = { Text(stringResource(R.string.llm_prompt_name_label)) },
                    singleLine = true,
                )
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = uiState.prompt,
                onValueChange = viewModel::updatePrompt,
                enabled = uiState.canEditPrompt,
                label = { Text(stringResource(R.string.llm_prompt_text_label)) },
                placeholder = { Text(stringResource(R.string.llm_prompt_text_placeholder)) },
                minLines = 12,
            )

            if (!uiState.canEditPrompt) {
                Text(
                    text = stringResource(R.string.llm_prompt_smart_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.saveEnabled,
                onClick = { viewModel.save(onSaved = onNavigateBack) },
            ) {
                Text(stringResource(R.string.llm_prompt_save))
            }

            if (uiState.resetEnabled) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::resetToOriginal,
                ) {
                    Text(stringResource(R.string.llm_prompt_reset))
                }
            }

            if (uiState.deleteEnabled) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.delete(onDeleted = onNavigateBack) },
                ) {
                    Text(
                        text = stringResource(R.string.llm_prompt_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
