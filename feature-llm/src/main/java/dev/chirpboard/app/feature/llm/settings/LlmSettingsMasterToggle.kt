package dev.chirpboard.app.feature.llm.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.feature.llm.R

import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.Icons

@Composable
internal fun LlmSettingsMasterToggleCard(
    uiState: LlmSettingsViewModel.UiState,
    onToggle: () -> Unit,
) {
    SettingsSwitchItem(
        icon = Icons.Default.Star,
        title = stringResource(R.string.llm_enable_processing_title),
        subtitle = if (uiState.llmEnabled) stringResource(R.string.llm_enable_processing_enabled) else stringResource(R.string.llm_enable_processing_disabled),
        checked = uiState.llmEnabled,
        onCheckedChange = { onToggle() },
    )
}
