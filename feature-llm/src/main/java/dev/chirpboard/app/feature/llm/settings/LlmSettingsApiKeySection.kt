package dev.chirpboard.app.feature.llm.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.components.SettingsDropdownListItem
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.feature.llm.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LlmSettingsApiKeySection(
    uiState: LlmSettingsViewModel.UiState,
    onProviderChanged: (LlmProvider) -> Unit,
    onModelChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
    onClear: () -> Unit,
    onDismissTestResult: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingsSectionHeader(title = stringResource(R.string.llm_provider_section_title))

        Text(
            text = stringResource(R.string.llm_provider_section_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.size(8.dp))

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LlmProviderSettingsDropdown(
                    selectedProvider = uiState.activeProvider,
                    onProviderChanged = onProviderChanged,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                LlmModelSettingsDropdown(
                    models = uiState.availableModels,
                    selectedModelId = uiState.selectedModelId,
                    onModelChanged = onModelChanged,
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        SettingsSectionHeader(title = stringResource(R.string.llm_credentials_section_title))

        Text(
            text = stringResource(R.string.llm_credentials_section_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.size(8.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LlmProviderIconBadge(
                provider = uiState.activeProvider,
                contentDescription =
                    stringResource(
                        R.string.llm_provider_icon_content_description,
                        uiState.activeProvider.displayName,
                    ),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiState.activeProvider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text =
                        if (uiState.isKeyConfigured) {
                            stringResource(R.string.llm_api_key_configured)
                        } else {
                            stringResource(R.string.llm_api_key_not_set)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ApiKeyStatusChip(isConfigured = uiState.isKeyConfigured)
        }

        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = onApiKeyChanged,
            label = { Text(stringResource(R.string.llm_api_key_label, uiState.activeProvider.displayName)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            enabled = uiState.isSecureStorageAvailable,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    if (uiState.isSecureStorageAvailable) {
                        stringResource(R.string.llm_enter_api_key)
                    } else {
                        stringResource(R.string.llm_secure_storage_unavailable)
                    },
                )
            },
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSave,
                enabled = uiState.isSecureStorageAvailable && uiState.apiKey.isNotBlank(),
            ) {
                Text(stringResource(R.string.llm_save))
            }

            FilledTonalButton(
                onClick = onTestConnection,
                enabled = uiState.isSecureStorageAvailable && uiState.apiKey.isNotBlank() && !uiState.isTestingConnection,
            ) {
                if (uiState.isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.llm_testing))
                } else {
                    Text(stringResource(R.string.llm_test_connection))
                }
            }

            if (uiState.isKeyConfigured) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.llm_clear))
                }
            }
        }

        uiState.connectionTestResult?.let { result ->
            ConnectionTestBanner(
                result = result,
                onDismiss = onDismissTestResult,
            )
        }

        Text(
            text = stringResource(R.string.llm_api_key_help, uiState.activeProvider.apiKeyHelpUrl),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LlmProviderSettingsDropdown(
    selectedProvider: LlmProvider,
    onProviderChanged: (LlmProvider) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }

    val textColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "providerDropdownText",
    )
    val iconTint by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "providerDropdownIcon",
    )

    Box {
        ListItem(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = true },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                LlmProviderIconBadge(
                    provider = selectedProvider,
                    contentDescription =
                        stringResource(
                            R.string.llm_provider_icon_content_description,
                            selectedProvider.displayName,
                        ),
                )
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.llm_provider_label),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.llm_provider_row_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = selectedProvider.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.llm_provider_label),
                        tint = iconTint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )

        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false },
        ) {
            LlmProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LlmProviderIcon(
                                provider = provider,
                                contentDescription =
                                    stringResource(
                                        R.string.llm_provider_icon_content_description,
                                        provider.displayName,
                                    ),
                                size = 20.dp,
                            )
                            Text(provider.displayName)
                        }
                    },
                    onClick = {
                        onProviderChanged(provider)
                        isExpanded = false
                    },
                    trailingIcon =
                        if (provider == selectedProvider) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(CoreR.string.desc_selected),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun LlmModelSettingsDropdown(
    models: List<LlmModelOption>,
    selectedModelId: String,
    onModelChanged: (String) -> Unit,
) {
    val selectedModel = models.firstOrNull { it.id == selectedModelId }
    val selectedLabel = selectedModel?.label ?: selectedModelId

    SettingsDropdownListItem(
        title = stringResource(R.string.llm_model_label),
        supportingText = stringResource(R.string.llm_model_supporting),
        options = models,
        selectedOption = selectedModel ?: LlmModelOption(selectedModelId, selectedLabel),
        optionLabel = { it.label },
        onOptionSelected = { onModelChanged(it.id) },
        additionalSupportingContent = {
            Text(
                text = selectedModelId,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun ApiKeyStatusChip(isConfigured: Boolean) {
    val containerColor by animateColorAsState(
        targetValue =
            if (isConfigured) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
            },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "api_key_chip_bg",
    )
    val contentColor by animateColorAsState(
        targetValue =
            if (isConfigured) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "api_key_chip_fg",
    )

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text =
                    if (isConfigured) {
                        stringResource(R.string.llm_api_key_configured)
                    } else {
                        stringResource(R.string.llm_api_key_not_set)
                    },
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun ConnectionTestBanner(
    result: LlmSettingsViewModel.ConnectionTestResult,
    onDismiss: () -> Unit,
) {
    val containerColor =
        when (result) {
            is LlmSettingsViewModel.ConnectionTestResult.Success -> MaterialTheme.colorScheme.primaryContainer
            is LlmSettingsViewModel.ConnectionTestResult.Error -> MaterialTheme.colorScheme.errorContainer
        }
    val contentColor =
        when (result) {
            is LlmSettingsViewModel.ConnectionTestResult.Success -> MaterialTheme.colorScheme.onPrimaryContainer
            is LlmSettingsViewModel.ConnectionTestResult.Error -> MaterialTheme.colorScheme.onErrorContainer
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector =
                        when (result) {
                            is LlmSettingsViewModel.ConnectionTestResult.Success -> Icons.Default.CheckCircle
                            is LlmSettingsViewModel.ConnectionTestResult.Error -> Icons.Default.Warning
                        },
                    contentDescription = null,
                    tint = contentColor,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text =
                        when (result) {
                            is LlmSettingsViewModel.ConnectionTestResult.Success -> {
                                stringResource(R.string.llm_connection_success)
                            }

                            is LlmSettingsViewModel.ConnectionTestResult.Error -> {
                                stringResource(R.string.llm_connection_error, result.message)
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.llm_connection_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.8f),
            )
        }
    }
}
