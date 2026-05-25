package dev.chirpboard.app.feature.llm.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import dev.chirpboard.app.feature.llm.R

@Composable
internal fun LlmSettingsProcessingSection(
    uiState: LlmSettingsViewModel.UiState,
    onSetAutoTitle: (Boolean) -> Unit,
    onSetAutoSummary: (Boolean) -> Unit,
    onManagePrompts: () -> Unit,
) {
    Column {
        SettingsSectionHeader(title = stringResource(R.string.llm_processing_title))

        ListItem(
            modifier = Modifier.clickable(onClick = onManagePrompts),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.llm_prompt_manage)) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.llm_prompt_manage_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
        )

        SettingsSwitchItem(
            icon = Icons.Default.Edit,
            title = stringResource(R.string.llm_auto_title_title),
            subtitle = if (!uiState.isKeyConfigured) "Requires API key to be configured." else stringResource(R.string.llm_auto_title_subtitle),
            checked = uiState.autoTitle,
            onCheckedChange = onSetAutoTitle,
            enabled = uiState.isKeyConfigured
        )

        SettingsSwitchItem(
            icon = Icons.Default.List,
            title = stringResource(R.string.llm_auto_summary_title),
            subtitle = if (!uiState.isKeyConfigured) "Requires API key to be configured." else stringResource(R.string.llm_auto_summary_subtitle),
            checked = uiState.autoSummary,
            onCheckedChange = onSetAutoSummary,
            enabled = uiState.isKeyConfigured
        )
    }
}
