package dev.chirpboard.app.feature.llm.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.core.ui.components.SettingsListItem
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

        SettingsListItem(
            icon = Icons.Default.Create,
            title = stringResource(R.string.llm_prompt_manage),
            subtitle = stringResource(R.string.llm_prompt_manage_help),
            onClick = onManagePrompts,
        )

        SettingsSwitchItem(
            icon = Icons.Default.Edit,
            title = stringResource(R.string.llm_auto_title_title),
            subtitle =
                if (!uiState.isKeyConfigured) {
                    stringResource(R.string.llm_requires_api_key)
                } else {
                    stringResource(R.string.llm_auto_title_subtitle)
                },
            checked = uiState.autoTitle,
            onCheckedChange = onSetAutoTitle,
            enabled = uiState.isKeyConfigured,
        )

        SettingsSwitchItem(
            icon = Icons.AutoMirrored.Filled.List,
            title = stringResource(R.string.llm_auto_summary_title),
            subtitle =
                if (!uiState.isKeyConfigured) {
                    stringResource(R.string.llm_requires_api_key)
                } else {
                    stringResource(R.string.llm_auto_summary_subtitle)
                },
            checked = uiState.autoSummary,
            onCheckedChange = onSetAutoSummary,
            enabled = uiState.isKeyConfigured,
        )
    }
}
