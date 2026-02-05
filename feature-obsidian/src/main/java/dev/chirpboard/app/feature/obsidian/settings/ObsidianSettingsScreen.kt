package dev.chirpboard.app.feature.obsidian.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings screen for configuring Obsidian vault integration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObsidianSettingsScreen(
    viewModel: ObsidianSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // SAF folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistable permission so we can access the folder later
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            viewModel.setVaultUri(it)
        }
    }

    // Refresh access status when screen becomes visible
    LaunchedEffect(Unit) {
        viewModel.refreshAccessStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Obsidian Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Vault configuration card
            VaultConfigurationCard(
                vaultName = uiState.vaultName,
                hasAccess = uiState.hasAccess,
                isConfigured = uiState.vaultUri != null,
                onSelectVault = { folderPickerLauncher.launch(null) },
                onClearVault = viewModel::clearVault
            )

            // Auto-export toggle (only shown when vault is configured)
            if (uiState.vaultUri != null) {
                AutoExportCard(
                    enabled = uiState.autoExportEnabled,
                    hasAccess = uiState.hasAccess,
                    onToggle = viewModel::toggleAutoExport
                )
            }

            // Help text
            HelpCard()
        }
    }
}

@Composable
private fun VaultConfigurationCard(
    vaultName: String?,
    hasAccess: Boolean,
    isConfigured: Boolean,
    onSelectVault: () -> Unit,
    onClearVault: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Obsidian Vault",
                style = MaterialTheme.typography.titleMedium
            )

            val accessIconTint by animateColorAsState(
                targetValue = if (hasAccess) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label = "access_icon_tint"
            )
            val accessTextColor by animateColorAsState(
                targetValue = if (hasAccess) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label = "access_text_color"
            )

            if (isConfigured) {
                // Show configured vault status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasAccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = accessIconTint
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = vaultName ?: "Unknown folder",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (hasAccess) "Access granted" else "Access lost - please re-select",
                            style = MaterialTheme.typography.bodySmall,
                            color = accessTextColor
                        )
                    }
                }

                // Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onSelectVault) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Change Vault")
                    }
                    OutlinedButton(onClick = onClearVault) {
                        Text("Clear")
                    }
                }
            } else {
                // Show not configured state
                Text(
                    text = "No vault configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(onClick = onSelectVault) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Select Vault Folder")
                }
            }
        }
    }
}

@Composable
private fun AutoExportCard(
    enabled: Boolean,
    hasAccess: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasAccess) { onToggle() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-export",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Automatically export recordings to Obsidian after transcription completes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = enabled,
                onCheckedChange = null, // Handled by card click
                enabled = hasAccess
            )
        }
    }
}

@Composable
private fun HelpCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "About Obsidian Integration",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Select a folder inside your Obsidian vault to export recordings as Markdown files. " +
                    "Each recording will be saved with YAML frontmatter containing metadata like title, date, duration, and tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Note: This app uses Android's Storage Access Framework (SAF) for secure file access. " +
                    "Permission is requested only for the specific folder you choose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
