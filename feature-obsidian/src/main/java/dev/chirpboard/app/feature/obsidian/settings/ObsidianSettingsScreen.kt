package dev.chirpboard.app.feature.obsidian.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.feature.obsidian.R

/**
 * Settings screen for configuring Obsidian vault integration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObsidianSettingsScreen(
    viewModel: ObsidianSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // SAF folder picker launcher
    val folderPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            uri?.let {
                // Take persistable permission so we can access the folder later
                val takeFlags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
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
                title = { Text(stringResource(R.string.obsidian_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.obsidian_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Vault configuration card
            VaultConfigurationCard(
                vaultName = uiState.vaultName,
                hasAccess = uiState.hasAccess,
                isConfigured = uiState.vaultUri != null,
                onSelectVault = { folderPickerLauncher.launch(null) },
                onClearVault = viewModel::clearVault,
            )

            // Auto-export toggle (only shown when vault is configured)
            if (uiState.vaultUri != null) {
                AutoExportCard(
                    enabled = uiState.autoExportEnabled,
                    hasAccess = uiState.hasAccess,
                    onToggle = viewModel::toggleAutoExport,
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
    onClearVault: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.obsidian_vault_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
        )

        val accessIconTint by animateColorAsState(
            targetValue =
                if (hasAccess) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "access_icon_tint",
        )
        val accessTextColor by animateColorAsState(
            targetValue =
                if (hasAccess) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "access_text_color",
        )

        if (isConfigured) {
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                headlineContent = {
                    Text(
                        text = vaultName ?: stringResource(R.string.obsidian_unknown_folder),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        text =
                            if (hasAccess) {
                                stringResource(R.string.obsidian_access_granted)
                            } else {
                                stringResource(R.string.obsidian_access_lost)
                            },
                        color = accessTextColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = if (hasAccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = accessIconTint,
                    )
                }
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onSelectVault) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.obsidian_change_vault))
                }
                OutlinedButton(onClick = onClearVault) {
                    Text(stringResource(R.string.obsidian_clear))
                }
            }
        } else {
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                headlineContent = {
                    Text(
                        text = stringResource(R.string.obsidian_no_vault_configured),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            )

            Button(
                onClick = onSelectVault,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.obsidian_select_vault_folder))
            }
        }
    }
}

@Composable
private fun AutoExportCard(
    enabled: Boolean,
    hasAccess: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(R.string.obsidian_auto_export_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.obsidian_auto_export_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = null,
                enabled = hasAccess,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasAccess) { onToggle() },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )
}

@Composable
private fun HelpCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.obsidian_help_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text =
                    stringResource(R.string.obsidian_help_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    stringResource(R.string.obsidian_help_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
