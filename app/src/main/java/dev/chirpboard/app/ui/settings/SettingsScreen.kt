package dev.chirpboard.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import dev.chirpboard.app.core.ui.components.SettingsBadge
import dev.chirpboard.app.core.ui.components.ChirpSettingsHubScaffold
import dev.chirpboard.app.core.ui.components.SettingsListItem
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader

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
    onNavigateToProfiles: () -> Unit,
    onNavigateToTags: () -> Unit,
    onNavigateToWordReplacements: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDevMenu: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
                    badge = SettingsBadge.NEW,
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

            // Bottom padding
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
