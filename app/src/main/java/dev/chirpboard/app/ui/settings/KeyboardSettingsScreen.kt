package dev.chirpboard.app.ui.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.SettingsBadge
import dev.chirpboard.app.core.ui.components.SettingsDropdownListItem
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.components.SettingsListItem
import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import dev.chirpboard.app.core.ui.components.StatusBadge
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.quickinput.QuickInputFocusRecoveryAccessibilityService
import kotlinx.coroutines.launch

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
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val systemSettingsOpenFailedMessage = stringResource(R.string.keyboard_settings_system_open_failed)
    val accessibilitySettingsOpenFailedMessage =
        stringResource(R.string.keyboard_settings_accessibility_open_failed)

    // PROP-5: reflect whether Chirp is already an enabled IME so the "Enable Keyboard" action can
    // de-emphasize once it's done. Re-checked on every resume — the user enables it on the system
    // settings page and returns here, so a one-shot read at first composition would go stale.
    var isKeyboardEnabled by remember { mutableStateOf(false) }
    var isXReplyCompatibilityEnabled by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        isKeyboardEnabled = isChirpKeyboardEnabled(context)
        isXReplyCompatibilityEnabled = isQuickInputFocusRecoveryEnabled(context)
        onPauseOrDispose { }
    }

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.keyboard_settings_title),
        onNavigateBack = onNavigateBack,
        scrollBehavior = scrollBehavior,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item {
                SettingsSectionHeader(title = stringResource(R.string.keyboard_settings_section_behavior))
            }
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
                    trailingIconContentDescription = stringResource(R.string.desc_select_mode),
                    additionalSupportingContent = {
                        if (!uiState.llmEnabled) {
                            Spacer(modifier = Modifier.height(ChirpSpacing.ExtraSmall))
                            Text(
                                text = stringResource(R.string.keyboard_settings_processing_mode_disabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.keyboard_settings_compatibility_title),
                )
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.AccessibilityNew,
                    title = stringResource(R.string.keyboard_settings_x_reply_fix_title),
                    subtitle =
                        if (isXReplyCompatibilityEnabled) {
                            stringResource(R.string.keyboard_settings_x_reply_fix_description_enabled)
                        } else {
                            stringResource(R.string.keyboard_settings_x_reply_fix_description)
                        },
                    badge =
                        if (isXReplyCompatibilityEnabled) {
                            SettingsBadge.CONNECTED
                        } else {
                            null
                        },
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: android.content.ActivityNotFoundException) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(accessibilitySettingsOpenFailedMessage)
                            }
                        }
                    },
                )
            }

            item {
                SettingsSectionHeader(title = stringResource(R.string.keyboard_settings_system_title))
            }
            item {
                // PROP-5: once Chirp is the enabled IME, a CONNECTED badge sits inline above the
                // description so the enabled state is visible without reading the button hierarchy.
                Column(
                    modifier =
                        Modifier.padding(
                            horizontal = ChirpSpacing.ScreenHorizontal,
                            vertical = ChirpSpacing.Small,
                        ),
                    verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
                ) {
                    if (isKeyboardEnabled) {
                        StatusBadge(badge = SettingsBadge.CONNECTED)
                    }
                    Text(
                        text =
                            if (isKeyboardEnabled) {
                                stringResource(R.string.keyboard_settings_system_description_enabled)
                            } else {
                                stringResource(R.string.keyboard_settings_system_description)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = ChirpSpacing.ScreenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
                ) {
                    // "Enable Keyboard" is the primary call to action — filled while Chirp isn't yet
                    // enabled, demoted to an OutlinedButton once it is so "Select Keyboard" reads as
                    // the next step. "Select Keyboard" is always the secondary action.
                    val enableKeyboard = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        } catch (_: android.content.ActivityNotFoundException) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(systemSettingsOpenFailedMessage)
                            }
                            Unit
                        }
                    }
                    if (isKeyboardEnabled) {
                        OutlinedButton(onClick = { enableKeyboard() }) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                modifier = Modifier.padding(end = ChirpSpacing.Small).size(18.dp),
                            )
                            Text(stringResource(R.string.enable_keyboard))
                        }
                        Button(
                            onClick = {
                                val imm =
                                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showInputMethodPicker()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Keyboard,
                                contentDescription = null,
                                modifier = Modifier.padding(end = ChirpSpacing.Small).size(18.dp),
                            )
                            Text(stringResource(R.string.select_keyboard))
                        }
                    } else {
                        Button(onClick = { enableKeyboard() }) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                modifier = Modifier.padding(end = ChirpSpacing.Small).size(18.dp),
                            )
                            Text(stringResource(R.string.enable_keyboard))
                        }
                        OutlinedButton(
                            onClick = {
                                val imm =
                                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showInputMethodPicker()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Keyboard,
                                contentDescription = null,
                                modifier = Modifier.padding(end = ChirpSpacing.Small).size(18.dp),
                            )
                            Text(stringResource(R.string.select_keyboard))
                        }
                    }
                }
            }

            // INS-7: reserve space under the list for the global mini-player sibling bar.
            item { Spacer(modifier = Modifier.height(ChirpSpacing.MiniPlayerClearance)) }
        }
    }
}

/**
 * PROP-5: a lightweight, read-only check for whether the Chirp IME is in the system's list of
 * enabled input methods. Matched on package name so it survives the IME service moving packages;
 * any failure (no IMM, security restriction) is treated as "not enabled" so the prominent
 * "Enable Keyboard" action stays visible.
 */
private fun isChirpKeyboardEnabled(context: Context): Boolean =
    try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.enabledInputMethodList.orEmpty().any { it.packageName == context.packageName }
    } catch (_: RuntimeException) {
        false
    }

/** Returns whether the user has enabled Chirp's package-limited X focus-recovery service. */
internal fun isQuickInputFocusRecoveryEnabled(context: Context): Boolean =
    try {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected =
            ComponentName(
                context,
                QuickInputFocusRecoveryAccessibilityService::class.java,
            )
        manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
            }
    } catch (_: RuntimeException) {
        false
    }
