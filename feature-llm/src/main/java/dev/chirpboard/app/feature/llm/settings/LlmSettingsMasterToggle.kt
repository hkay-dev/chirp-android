package dev.chirpboard.app.feature.llm.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import dev.chirpboard.app.feature.llm.R

@Composable
internal fun LlmSettingsMasterToggleCard(
    uiState: LlmSettingsViewModel.UiState,
    onToggle: () -> Unit,
) {
    SettingsSwitchItem(
        icon = Icons.Default.Star,
        title = stringResource(R.string.llm_enable_processing_title),
        subtitle =
            if (uiState.llmEnabled) {
                stringResource(R.string.llm_enable_processing_enabled)
            } else {
                stringResource(R.string.llm_enable_processing_disabled)
            },
        checked = uiState.llmEnabled,
        onCheckedChange = { onToggle() },
    )
}
