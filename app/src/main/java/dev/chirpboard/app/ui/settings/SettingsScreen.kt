package dev.chirpboard.app.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Main settings hub screen that organizes all app settings by category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToLlmSettings: () -> Unit,
    onNavigateToObsidianSettings: () -> Unit,
    onNavigateToKeyboardSettings: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToTags: () -> Unit,
    onNavigateToWordReplacements: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
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
            // AI & Processing
            item {
                SectionHeader(
                    title = "AI & Processing",
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Psychology,
                    title = "LLM Settings",
                    subtitle = "Configure AI processing and API keys",
                    onClick = onNavigateToLlmSettings
                )
            }

            // Integrations
            item {
                SectionHeader(
                    title = "Integrations",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.FolderOpen,
                    title = "Obsidian",
                    subtitle = "Export recordings to Obsidian vault",
                    onClick = onNavigateToObsidianSettings
                )
            }

            // Keyboard
            item {
                SectionHeader(
                    title = "Keyboard",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Keyboard,
                    title = "Keyboard Settings",
                    subtitle = "Recording, processing, and input options",
                    onClick = onNavigateToKeyboardSettings
                )
            }

            // Organization
            item {
                SectionHeader(
                    title = "Organization",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Person,
                    title = "Profiles",
                    subtitle = "Manage recording profiles",
                    onClick = onNavigateToProfiles
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Label,
                    title = "Tags",
                    subtitle = "Organize recordings with tags",
                    onClick = onNavigateToTags
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.SwapHoriz,
                    title = "Word Replacements",
                    subtitle = "Auto-correct transcription words",
                    onClick = onNavigateToWordReplacements
                )
            }

            // About
            item {
                SectionHeader(
                    title = "About",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "About",
                    subtitle = "Version ${uiState.appVersion}",
                    onClick = onNavigateToAbout
                )
            }
        }
    }
}
