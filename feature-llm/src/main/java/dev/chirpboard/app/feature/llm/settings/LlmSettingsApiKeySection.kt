package dev.chirpboard.app.feature.llm.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.feature.llm.R
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader

@Composable
internal fun LlmSettingsApiKeySection(
    uiState: LlmSettingsViewModel.UiState,
    onApiKeyChanged: (String) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(title = "API Configuration")
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = {
                Text(
                    text =
                        if (uiState.isKeyConfigured) {
                            stringResource(R.string.llm_api_key_configured)
                        } else {
                            stringResource(R.string.llm_api_key_not_set)
                        },
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            leadingContent = {
                val keyStatusTint by animateColorAsState(
                    targetValue =
                        if (uiState.isKeyConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    label = "key_status_tint",
                )
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector =
                            if (uiState.isKeyConfigured) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Warning
                            },
                        contentDescription = null,
                        tint = keyStatusTint,
                    )
                }
            }
        )

        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = onApiKeyChanged,
            label = { Text(stringResource(R.string.llm_gemini_api_key)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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

        Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSave,
                enabled = uiState.isSecureStorageAvailable && uiState.apiKey.isNotBlank(),
            ) {
                Text(stringResource(R.string.llm_save))
            }

            OutlinedButton(
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
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.medium,
                color =
                    when (result) {
                        is LlmSettingsViewModel.ConnectionTestResult.Success -> {
                            MaterialTheme.colorScheme.primaryContainer
                        }

                        is LlmSettingsViewModel.ConnectionTestResult.Error -> {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector =
                            when (result) {
                                is LlmSettingsViewModel.ConnectionTestResult.Success -> {
                                    Icons.Default.CheckCircle
                                }

                                is LlmSettingsViewModel.ConnectionTestResult.Error -> {
                                    Icons.Default.Warning
                                }
                            },
                        contentDescription = null,
                        tint =
                            when (result) {
                                is LlmSettingsViewModel.ConnectionTestResult.Success -> {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                }

                                is LlmSettingsViewModel.ConnectionTestResult.Error -> {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                            },
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
                        color =
                            when (result) {
                                is LlmSettingsViewModel.ConnectionTestResult.Success -> {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                }

                                is LlmSettingsViewModel.ConnectionTestResult.Error -> {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                            },
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.llm_api_key_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
