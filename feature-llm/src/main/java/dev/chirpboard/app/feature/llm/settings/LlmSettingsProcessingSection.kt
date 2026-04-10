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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.feature.llm.R
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader

import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.Icons

@Composable
internal fun LlmSettingsProcessingSection(
    uiState: LlmSettingsViewModel.UiState,
    onSetAutoTitle: (Boolean) -> Unit,
    onSetAutoSummary: (Boolean) -> Unit,
) {
    Column {
        SettingsSectionHeader(title = stringResource(R.string.llm_processing_title))

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
