package dev.chirpboard.app.feature.obsidian.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
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

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.obsidian_settings_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Large),
        ) {
            // Vault configuration
            VaultConfigurationCard(
                vaultName = uiState.vaultName,
                hasAccess = uiState.hasAccess,
                isConfigured = uiState.vaultUri != null,
                onSelectVault = { folderPickerLauncher.launch(null) },
                onClearVault = viewModel::clearVault,
            )

            // Auto-export toggle (only shown when vault is configured)
            PushDownReveal(visible = uiState.vaultUri != null) {
                AutoExportRow(
                    enabled = uiState.autoExportEnabled,
                    hasAccess = uiState.hasAccess,
                    onToggle = viewModel::toggleAutoExport,
                )
            }

            // Help text
            HelpSection()

            Spacer(Modifier.height(ChirpSpacing.MiniPlayerClearance))
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
        verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
    ) {
        SettingsSectionHeader(
            title = stringResource(R.string.obsidian_vault_title),
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
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Text(
                        text = vaultName ?: stringResource(R.string.obsidian_unknown_folder),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = if (hasAccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = accessIconTint,
                    )
                },
            )

            Row(
                modifier = Modifier.padding(horizontal = ChirpSpacing.ScreenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
            ) {
                Button(onClick = onSelectVault) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(ChirpSpacing.Small))
                    Text(stringResource(R.string.obsidian_change_vault))
                }
                OutlinedButton(onClick = onClearVault) {
                    Text(stringResource(R.string.obsidian_clear))
                }
            }
        } else {
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Text(
                        text = stringResource(R.string.obsidian_no_vault_configured),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                },
            )

            Button(
                onClick = onSelectVault,
                modifier = Modifier.padding(horizontal = ChirpSpacing.ScreenHorizontal),
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                )
                Spacer(Modifier.width(ChirpSpacing.Small))
                Text(stringResource(R.string.obsidian_select_vault_folder))
            }
        }
    }
}

@Composable
private fun AutoExportRow(
    enabled: Boolean,
    hasAccess: Boolean,
    onToggle: () -> Unit,
) {
    SettingsSwitchItem(
        icon = Icons.Default.Sync,
        title = stringResource(R.string.obsidian_auto_export_title),
        subtitle =
            if (hasAccess) {
                stringResource(R.string.obsidian_auto_export_subtitle)
            } else {
                stringResource(R.string.obsidian_auto_export_needs_access)
            },
        checked = enabled,
        onCheckedChange = { onToggle() },
        enabled = hasAccess,
    )
}

@Composable
private fun HelpSection() {
    Column(
        modifier = Modifier.padding(horizontal = ChirpSpacing.ScreenHorizontal),
        verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
    ) {
        Text(
            text = stringResource(R.string.obsidian_help_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.obsidian_help_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.obsidian_help_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
