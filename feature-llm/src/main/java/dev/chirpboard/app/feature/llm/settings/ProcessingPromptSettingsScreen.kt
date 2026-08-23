package dev.chirpboard.app.feature.llm.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.SettingsDropdownListItem
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.feature.llm.R
import dev.chirpboard.app.feature.llm.model.ProcessingPromptPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingPromptSettingsScreen(
    onNavigateBack: () -> Unit,
    onEditPreset: (String) -> Unit,
    onAddPreset: () -> Unit,
    viewModel: ProcessingPromptSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var presetToDelete by remember { mutableStateOf<ProcessingPromptPreset?>(null) }

    presetToDelete?.let { preset ->
        // VIS-7: route through the shared AnimatedAlertDialog so every dialog shares one entrance.
        AnimatedAlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text(stringResource(R.string.llm_prompt_delete_title)) },
            text = { Text(stringResource(R.string.llm_prompt_delete_body, preset.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCustomPreset(preset.id)
                        presetToDelete = null
                    },
                ) {
                    Text(stringResource(R.string.llm_prompt_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text(stringResource(R.string.llm_passphrase_cancel))
                }
            },
        )
    }

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.llm_prompt_settings_title),
        onNavigateBack = onNavigateBack,
        scrollBehavior = scrollBehavior,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(ChirpSpacing.ExtraSmall),
        ) {
            uiState.statusMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.padding(
                                horizontal = ChirpSpacing.ScreenHorizontal,
                                vertical = ChirpSpacing.Small,
                            ),
                    )
                }
            }

            item {
                SettingsSectionHeader(title = stringResource(R.string.llm_prompt_default_section))
            }
            item {
                SettingsDropdownListItem(
                    title = stringResource(R.string.llm_prompt_default_mode),
                    supportingText = stringResource(R.string.llm_prompt_default_mode_help),
                    options = uiState.selectableModes.map { it.id },
                    selectedOption = uiState.defaultModeId,
                    optionLabel = { modeId ->
                        uiState.selectableModes.firstOrNull { it.id == modeId }?.name ?: modeId
                    },
                    onOptionSelected = viewModel::setDefaultMode,
                )
            }

            item {
                SettingsSectionHeader(title = stringResource(R.string.llm_prompt_presets_section))
            }

            item {
                ListItem(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onAddPreset),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.llm_prompt_add),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                    },
                )
            }

            itemsIndexed(uiState.presets, key = { _, preset -> preset.id }) { index, preset ->
                PromptPresetListItem(
                    preset = preset,
                    onClick = {
                        if (preset.canEditPrompt) {
                            onEditPreset(preset.id)
                        }
                    },
                    onLongClick = {
                        if (!preset.isBuiltIn) {
                            presetToDelete = preset
                        }
                    },
                )
                if (index < uiState.presets.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ChirpSpacing.ScreenHorizontal),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.llm_prompt_presets_footer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = ChirpSpacing.ScreenHorizontal,
                            vertical = ChirpSpacing.Medium,
                        ),
                )
            }

            item {
                Spacer(Modifier.height(ChirpSpacing.MiniPlayerClearance))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PromptPresetListItem(
    preset: ProcessingPromptPreset,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = preset.canEditPrompt,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text =
                    when {
                        !preset.canEditPrompt -> stringResource(R.string.llm_prompt_smart_help)
                        preset.isModified -> stringResource(R.string.llm_prompt_modified)
                        preset.isBuiltIn -> stringResource(R.string.llm_prompt_builtin)
                        else -> stringResource(R.string.llm_prompt_custom)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (preset.canEditPrompt) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        },
    )
}
