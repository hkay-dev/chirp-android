package dev.chirpboard.app.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.SettingsDropdownListItem
import dev.chirpboard.app.core.ui.components.SettingsSwitchItem

private val KeyboardProcessingModeIds = listOf(null, "proofread", "formal", "casual", "email", "code", "smart")

@Composable
private fun keyboardProcessingModeLabel(modeId: String?): String =
    when (modeId) {
        null -> stringResource(R.string.keyboard_settings_mode_global)
        "proofread" -> stringResource(R.string.keyboard_settings_mode_proofread)
        "formal" -> stringResource(R.string.keyboard_settings_mode_formal)
        "casual" -> stringResource(R.string.keyboard_settings_mode_casual)
        "email" -> stringResource(R.string.keyboard_settings_mode_email)
        "code" -> stringResource(R.string.keyboard_settings_mode_code)
        "smart" -> stringResource(R.string.keyboard_settings_mode_smart)
        else -> stringResource(R.string.keyboard_settings_mode_global)
    }

/**
 * Settings screen for keyboard-specific options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsScreen(
    viewModel: KeyboardSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.keyboard_settings_title),
        onNavigateBack = onNavigateBack,
        scrollBehavior = scrollBehavior,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item {
                SettingsSwitchItem(
                    icon = Icons.Rounded.Mic,
                    title = stringResource(R.string.keyboard_settings_save_recordings_title),
                    subtitle = stringResource(R.string.keyboard_settings_save_recordings_description),
                    checked = uiState.saveKeyboardRecordings,
                    onCheckedChange = { viewModel.toggleSaveRecordings() },
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.keyboard_settings_enable_llm_title),
                    subtitle = stringResource(R.string.keyboard_settings_enable_llm_description),
                    checked = uiState.llmEnabled,
                    onCheckedChange = { viewModel.toggleLlmEnabled() },
                )
            }

            item {
                SettingsDropdownListItem(
                    title = stringResource(R.string.keyboard_settings_processing_mode_title),
                    supportingText = stringResource(R.string.keyboard_settings_processing_mode_description),
                    options = KeyboardProcessingModeIds,
                    selectedOption = uiState.defaultProcessingMode,
                    optionLabel = { keyboardProcessingModeLabel(it) },
                    onOptionSelected = viewModel::setProcessingMode,
                    enabled = uiState.llmEnabled,
                    leadingContent = { Box(Modifier.size(40.dp)) },
                    trailingIconContentDescription = stringResource(R.string.desc_select_mode),
                    additionalSupportingContent = {
                        if (!uiState.llmEnabled) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.keyboard_settings_processing_mode_disabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.keyboard_settings_system_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    supportingContent = {
                        Column {
                            Text(
                                text = stringResource(R.string.keyboard_settings_system_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            // Ignore
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                    )
                                    Text(stringResource(R.string.enable_keyboard))
                                }
                                FilledTonalButton(
                                    onClick = {
                                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                        imm.showInputMethodPicker()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Keyboard,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                    )
                                    Text(stringResource(R.string.select_keyboard))
                                }
                            }
                        }
                    },
                    leadingContent = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .size(40.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                    ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Keyboard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
