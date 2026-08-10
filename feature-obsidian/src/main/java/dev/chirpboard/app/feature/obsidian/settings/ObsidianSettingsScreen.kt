package dev.chirpboard.app.feature.obsidian.settings

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.feature.obsidian.R
import dev.chirpboard.app.core.ui.R as CoreUiR

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

    // SAF folder picker launcher. Persisting the grant happens in the ViewModel: it is a
    // Binder call that can also throw, so it must run off the main thread with its failure
    // surfaced instead of crashing the screen.
    val folderPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            uri?.let(viewModel::setVaultUri)
        }

    // Re-check SAF access every time the screen comes back to the foreground, not just on
    // first composition: the user can revoke or move the vault folder in the system Files
    // app and return here, and the "connected" badge must not lie.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccessStatus()
        onPauseOrDispose { }
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
            // Wait for the stored preferences before drawing the vault card: rendering the
            // defaults would flash "No vault configured" at users who have one.
            if (!uiState.isLoading) {
                // Vault configuration
                VaultConfigurationCard(
                    vaultName = uiState.vaultName,
                    hasAccess = uiState.hasAccess,
                    isConfigured = uiState.vaultUri != null,
                    onSelectVault = { folderPickerLauncher.launch(null) },
                    onClearVault = viewModel::clearVault,
                )

                if (uiState.vaultSelectionFailed) {
                    Text(
                        text = stringResource(R.string.obsidian_vault_selection_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = ChirpSpacing.ScreenHorizontal),
                    )
                }

                // Auto-export toggle (only shown when vault is configured)
                PushDownReveal(visible = uiState.vaultUri != null) {
                    AutoExportRow(
                        enabled = uiState.autoExportEnabled,
                        hasAccess = uiState.hasAccess,
                        onToggle = viewModel::toggleAutoExport,
                    )
                }
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(CoreUiR.drawable.ic_obsidian),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(BrandGlyphSize),
            )
            Text(
                text = stringResource(R.string.obsidian_help_title),
                style = MaterialTheme.typography.titleSmall,
            )
        }
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

/** Leading brand glyph size for the help-section title; matches a titleSmall cap height. */
private val BrandGlyphSize = 20.dp
