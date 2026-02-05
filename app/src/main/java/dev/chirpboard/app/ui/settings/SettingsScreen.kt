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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onNavigateToDevMenu: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // AI & Processing Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader(title = "AI & Processing")
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.RecordVoiceOver,
                    title = "Transcription",
                    subtitle = "Voice recognition and model settings",
                    onClick = onNavigateToTranscriptionSettings
                )
            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "LLM Settings",
                    subtitle = "Configure AI processing and API keys",
                    badge = SettingsBadge.NEW,
                    onClick = onNavigateToLlmSettings
                )
            }

            // Integrations Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = "Integrations")
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.FolderOpen,
                    title = "Obsidian",
                    subtitle = "Export recordings to Obsidian vault",
                    badge = if (uiState.isObsidianConnected) SettingsBadge.CONNECTED else null,
                    onClick = onNavigateToObsidianSettings
                )
            }

            // Audio Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = "Audio")
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Tune,
                    title = "Audio Settings",
                    subtitle = "Microphone gain, quality, and format",
                    onClick = onNavigateToAudioSettings
                )
            }

            // Keyboard Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = "Keyboard")
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Keyboard,
                    title = "Keyboard Settings",
                    subtitle = "Input and processing options",
                    onClick = onNavigateToKeyboardSettings
                )
            }

            // Organization Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = "Organization")
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Label,
                    title = "Tags",
                    subtitle = "Organize recordings with tags",
                    onClick = onNavigateToTags
                )
            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = "Profiles",
                    subtitle = "Manage recording profiles",
                    onClick = onNavigateToProfiles
                )
            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.SwapHoriz,
                    title = "Word Replacements",
                    subtitle = "Auto-correct transcription words",
                    onClick = onNavigateToWordReplacements
                )
            }

            // About Section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(title = "About")
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Info,
                    title = "App Info",
                    subtitle = "Version ${uiState.appVersion}",
                    onClick = onNavigateToAbout
                )
            }

            // Developer Menu (debug builds only)
            if (uiState.isDebugBuild) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
                item {
                    SettingsItem(
                        icon = Icons.Rounded.Code,
                        title = "Developer Menu",
                        subtitle = "Debug tools and diagnostics",
                        onClick = onNavigateToDevMenu
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
