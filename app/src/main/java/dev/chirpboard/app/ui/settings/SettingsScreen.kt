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
import androidx.compose.material.icons.rounded.Label
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
import dev.chirpboard.app.core.ui.components.SettingsItem
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // AI & Processing Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader(title = stringResource(R.string.settings_section_ai_processing))
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.RecordVoiceOver,
                    title = stringResource(R.string.settings_transcription_title),
                    subtitle = stringResource(R.string.settings_transcription_subtitle),
                    onClick = onNavigateToTranscriptionSettings,
                )
            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.settings_llm_title),
                    subtitle = stringResource(R.string.settings_llm_subtitle),
                    badge = SettingsBadge.NEW,
                    onClick = onNavigateToLlmSettings,
                )
            }

            // Integrations Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = stringResource(R.string.settings_section_integrations))
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.FolderOpen,
                    title = stringResource(R.string.settings_obsidian_title),
                    subtitle = stringResource(R.string.settings_obsidian_subtitle),
                    badge = if (uiState.isObsidianConnected) SettingsBadge.CONNECTED else null,
                    onClick = onNavigateToObsidianSettings,
                )
            }

            // Keyboard Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = stringResource(R.string.settings_section_keyboard))
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Keyboard,
                    title = stringResource(R.string.settings_keyboard_title),
                    subtitle = stringResource(R.string.settings_keyboard_subtitle),
                    onClick = onNavigateToKeyboardSettings,
                )
            }

            // Audio Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = stringResource(R.string.settings_section_audio))
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_audio_title),
                    subtitle = stringResource(R.string.settings_audio_subtitle),
                    onClick = onNavigateToAudioSettings,
                )
            }
            // Organization Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = stringResource(R.string.settings_section_organization))
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Label,
                    title = stringResource(R.string.settings_tags_title),
                    subtitle = stringResource(R.string.settings_tags_subtitle),
                    onClick = onNavigateToTags,
                )
            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = stringResource(R.string.settings_profiles_title),
                    subtitle = stringResource(R.string.settings_profiles_subtitle),
                    onClick = onNavigateToProfiles,
                )
            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.SwapHoriz,
                    title = stringResource(R.string.settings_word_replacements_title),
                    subtitle = stringResource(R.string.settings_word_replacements_subtitle),
                    onClick = onNavigateToWordReplacements,
                )
            }

            // About Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = stringResource(R.string.settings_section_about))
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.settings_app_info_title),
                    subtitle = stringResource(R.string.settings_app_info_subtitle, uiState.appVersion),
                    onClick = onNavigateToAbout,
                )
            }

            // Developer Menu (debug builds only)
            if (uiState.isDebugBuild) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    SettingsSectionHeader(title = stringResource(R.string.settings_section_developer))
                }
                item {
                    SettingsItem(
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
