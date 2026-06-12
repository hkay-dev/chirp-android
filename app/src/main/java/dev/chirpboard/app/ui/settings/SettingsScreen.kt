package dev.chirpboard.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import dev.chirpboard.app.core.ui.components.SettingsBadge
import dev.chirpboard.app.core.ui.components.ChirpSettingsHubScaffold
import dev.chirpboard.app.core.ui.components.SettingsListItem
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import dev.chirpboard.app.core.ui.haptics.ChirpHaptics

/**
 * Main settings hub screen that organizes all app settings by category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToTranscriptionSettings: () -> Unit = {},
    onNavigateToLlmSettings: () -> Unit,
    onNavigateToAudioSettings: () -> Unit = {},
    onNavigateToObsidianSettings: () -> Unit,
    onNavigateToKeyboardSettings: () -> Unit,
    onNavigateToBackupRestore: () -> Unit = {},
    onNavigateToProfiles: () -> Unit,
    onNavigateToTags: () -> Unit,
    onNavigateToWordReplacements: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDevMenu: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    ChirpSettingsHubScaffold(
        title = stringResource(R.string.settings_title),
        onNavigateBack = onNavigateBack,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        scrollBehavior = scrollBehavior,
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // AI & Processing Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_ai_processing))
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.RecordVoiceOver,
                    title = stringResource(R.string.settings_transcription_title),
                    subtitle = stringResource(R.string.settings_transcription_subtitle),
                    onClick = onNavigateToTranscriptionSettings,
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.settings_llm_title),
                    subtitle = stringResource(R.string.settings_llm_subtitle),
                    onClick = onNavigateToLlmSettings,
                )
            }

            // Integrations Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_integrations))
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.FolderOpen,
                    title = stringResource(R.string.settings_obsidian_title),
                    subtitle = stringResource(R.string.settings_obsidian_subtitle),
                    badge = if (uiState.isObsidianConnected) SettingsBadge.CONNECTED else null,
                    onClick = onNavigateToObsidianSettings,
                )
            }

            // Keyboard Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_keyboard))
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.Keyboard,
                    title = stringResource(R.string.settings_keyboard_title),
                    subtitle = stringResource(R.string.settings_keyboard_subtitle),
                    onClick = onNavigateToKeyboardSettings,
                )
            }

            // Audio Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_audio))
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_audio_title),
                    subtitle = stringResource(R.string.settings_audio_subtitle),
                    onClick = onNavigateToAudioSettings,
                )
            }

            // Appearance Section — "Use system colors (Material You)" (DECISIONS Color/brand).
            // Default OFF so the brand lavender palette stays the default; opting in derives color
            // from the wallpaper for both the app and the keyboard.
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_appearance))
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.settings_dynamic_color_title),
                    subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                    checked = uiState.useDynamicColor,
                    onCheckedChange = { enabled ->
                        // PRM-7: a light confirming tick on toggle, the app-level haptic language.
                        ChirpHaptics.tap(context)
                        viewModel.setUseDynamicColor(enabled)
                    },
                )
            }

            // Organization Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_organization))
            }
            item {
                SettingsListItem(
                    icon = Icons.AutoMirrored.Rounded.Label,
                    title = stringResource(R.string.settings_tags_title),
                    subtitle = stringResource(R.string.settings_tags_subtitle),
                    onClick = onNavigateToTags,
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.Person,
                    title = stringResource(R.string.settings_profiles_title),
                    subtitle = stringResource(R.string.settings_profiles_subtitle),
                    onClick = onNavigateToProfiles,
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.SwapHoriz,
                    title = stringResource(R.string.settings_word_replacements_title),
                    subtitle = stringResource(R.string.settings_word_replacements_subtitle),
                    onClick = onNavigateToWordReplacements,
                )
            }

            // Data Section — unified Backup & Restore for user data (chirp-backup v1).
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_data))
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.SettingsBackupRestore,
                    title = stringResource(R.string.settings_backup_title),
                    subtitle = stringResource(R.string.settings_backup_subtitle),
                    onClick = onNavigateToBackupRestore,
                )
            }

            // About Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_about))
            }
            item {
                SettingsListItem(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.settings_app_info_title),
                    subtitle = stringResource(R.string.settings_app_info_subtitle, uiState.appVersion),
                    onClick = onNavigateToAbout,
                )
            }

            // Developer Menu (debug builds only)
            if (uiState.isDebugBuild) {
                item {
                    SettingsSectionHeader(title = stringResource(R.string.settings_section_developer))
                }
                item {
                    SettingsListItem(
                        icon = Icons.Rounded.Code,
                        title = stringResource(R.string.dev_menu_title),
                        subtitle = stringResource(R.string.settings_dev_menu_subtitle),
                        onClick = onNavigateToDevMenu,
                    )
                }
            }

            // INS-7: reserve bottom space for the global mini-player. The bar is a layout sibling
            // that shrinks the NavHost area, so the last settings row can otherwise butt right up
            // against it. A generous bottom spacer keeps a Home-like breathing room (96dp ~ mini
            // player + margin) regardless of whether the bar is currently visible.
            item {
                Spacer(modifier = Modifier.height(SettingsBottomSpacing))
            }
        }
    }
}

/** INS-7: bottom inset reserved under settings lists so the global mini-player never crowds the
 *  last row. Mirrors the Home list's 96dp FAB/mini-player clearance. */
private val SettingsBottomSpacing = 96.dp
