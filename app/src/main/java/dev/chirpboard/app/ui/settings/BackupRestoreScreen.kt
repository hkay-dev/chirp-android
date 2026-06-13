package dev.chirpboard.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import dev.chirpboard.app.backup.BackupImportMode
import dev.chirpboard.app.backup.BackupSection
import dev.chirpboard.app.backup.ChirpBackupContents
import dev.chirpboard.app.backup.ChirpBackupManager
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.ChirpPill
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.haptics.ChirpHaptics
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.ui.settings.BackupRestoreViewModel.ImportState
import dev.chirpboard.app.ui.settings.BackupRestoreViewModel.PassphrasePromptMode
import dev.chirpboard.app.ui.settings.BackupRestoreViewModel.StatusMessage
import java.text.DateFormat
import java.util.Date

/** Sections in display order. */
private val SectionOrder =
    listOf(
        BackupSection.SETTINGS,
        BackupSection.TAGS,
        BackupSection.PROFILES,
        BackupSection.WORD_REPLACEMENTS,
        BackupSection.PROCESSING_PRESETS,
        BackupSection.API_KEYS,
    )

/**
 * Unified Backup & Restore: export selected sections to a chirp-backup JSON file and restore
 * them with explicit merge/replace semantics. API keys ride along only in the existing
 * passphrase-encrypted container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri -> viewModel.onExportFileChosen(uri) }
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri -> viewModel.onImportFileChosen(uri) }

    LaunchedEffect(viewModel) {
        viewModel.filePickerRequests.collect { request ->
            when (request) {
                is BackupRestoreViewModel.FilePickerRequest.CreateBackupFile ->
                    exportLauncher.launch(request.suggestedName)

                BackupRestoreViewModel.FilePickerRequest.OpenBackupFile ->
                    importLauncher.launch(arrayOf("application/json", "*/*"))
            }
        }
    }

    // A completed restore gets the success haptic the rest of the app uses for confirmations.
    LaunchedEffect(uiState.importState is ImportState.Complete) {
        if (uiState.importState is ImportState.Complete) {
            ChirpHaptics.success(context)
        }
    }

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.backup_restore_title),
        onNavigateBack = onNavigateBack,
        scrollBehavior = scrollBehavior,
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            item {
                Text(
                    text = stringResource(R.string.backup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = ChirpSpacing.ScreenHorizontal,
                            vertical = ChirpSpacing.Small,
                        ),
                )
            }

            item { SettingsSectionHeader(title = stringResource(R.string.backup_export_header)) }
            item {
                ExportCard(
                    uiState = uiState,
                    onToggleSection = { section ->
                        ChirpHaptics.tap(context)
                        viewModel.toggleExportSection(section)
                    },
                    onExport = {
                        ChirpHaptics.tap(context)
                        viewModel.startExport()
                    },
                )
            }
            uiState.exportMessage?.let { message ->
                item {
                    StatusBanner(message = message, onDismiss = viewModel::dismissExportMessage)
                }
            }

            item { SettingsSectionHeader(title = stringResource(R.string.backup_import_header)) }
            item {
                ImportContent(
                    importState = uiState.importState,
                    importMessage = uiState.importMessage,
                    onChooseFile = {
                        ChirpHaptics.tap(context)
                        viewModel.chooseImportFile()
                    },
                    onToggleSection = { section ->
                        ChirpHaptics.tap(context)
                        viewModel.toggleImportSection(section)
                    },
                    onSetMode = viewModel::setImportMode,
                    onApply = {
                        ChirpHaptics.tap(context)
                        viewModel.requestApplyImport()
                    },
                    onCancel = viewModel::resetImport,
                    onDone = viewModel::resetImport,
                    onDismissMessage = viewModel::dismissImportMessage,
                )
            }

            // Same bottom clearance as the settings hub so the global mini-player never crowds
            // the last row (INS-7).
            item { Spacer(modifier = Modifier.height(ChirpSpacing.MiniPlayerClearance)) }
        }
    }

    uiState.passphrasePrompt?.let { mode ->
        BackupPassphraseDialog(
            mode = mode,
            onDismiss = viewModel::cancelPassphrasePrompt,
            onConfirm = viewModel::submitPassphrase,
        )
    }

    val ready = uiState.importState as? ImportState.Ready
    if (ready?.showConfirmDialog == true) {
        ImportConfirmDialog(
            mode = ready.mode,
            onDismiss = viewModel::dismissConfirmDialog,
            onConfirm = {
                ChirpHaptics.tap(context)
                viewModel.confirmImport()
            },
        )
    }
}

// region Export

@Composable
private fun ExportCard(
    uiState: BackupRestoreViewModel.UiState,
    onToggleSection: (BackupSection) -> Unit,
    onExport: () -> Unit,
) {
    SectionCard {
        val counts = uiState.counts
        if (counts == null) {
            Row(
                modifier = Modifier.padding(ChirpSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.backup_loading_counts),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@SectionCard
        }

        SectionOrder.forEach { section ->
            val count = counts.countFor(section)
            val enabled =
                when (section) {
                    BackupSection.API_KEYS -> count > 0 && counts.isSecureStorageAvailable
                    BackupSection.SETTINGS -> true
                    else -> count > 0
                }
            BackupSectionRow(
                section = section,
                count = count,
                checked = section in uiState.exportSelection,
                enabled = enabled,
                onToggle = { onToggleSection(section) },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = ChirpSpacing.Large))

        Column(
            modifier = Modifier.padding(ChirpSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
        ) {
            Text(
                text = stringResource(R.string.backup_api_keys_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onExport,
                enabled = uiState.exportSelection.isNotEmpty() && !uiState.isExporting,
            ) {
                if (uiState.isExporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(ChirpSpacing.Small))
                }
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(ChirpSpacing.Small))
                Text(stringResource(R.string.backup_export_button))
            }
        }
    }
}

// endregion

// region Import

@Composable
private fun ImportContent(
    importState: ImportState,
    importMessage: StatusMessage?,
    onChooseFile: () -> Unit,
    onToggleSection: (BackupSection) -> Unit,
    onSetMode: (BackupImportMode) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    when (importState) {
        ImportState.Idle ->
            Column {
                SectionCard {
                    Column(
                        modifier = Modifier.padding(ChirpSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
                    ) {
                        Text(
                            text = stringResource(R.string.backup_import_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onChooseFile) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(ChirpSpacing.Small))
                            Text(stringResource(R.string.backup_choose_file))
                        }
                    }
                }
                importMessage?.let { message ->
                    StatusBanner(message = message, onDismiss = onDismissMessage)
                }
            }

        ImportState.Inspecting -> ProgressRow(text = stringResource(R.string.backup_inspecting))

        is ImportState.Ready ->
            ImportReadyCard(
                ready = importState,
                onToggleSection = onToggleSection,
                onSetMode = onSetMode,
                onApply = onApply,
                onCancel = onCancel,
            )

        ImportState.Applying -> ProgressRow(text = stringResource(R.string.backup_applying))

        is ImportState.Complete -> ImportResultCard(summary = importState.summary, onDone = onDone)
    }
}

@Composable
private fun ImportReadyCard(
    ready: ImportState.Ready,
    onToggleSection: (BackupSection) -> Unit,
    onSetMode: (BackupImportMode) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard {
        Column(
            modifier = Modifier.padding(ChirpSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ChirpSpacing.ExtraSmall),
        ) {
            Text(
                text = stringResource(R.string.backup_file_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            BackupFileMetadata(contents = ready.contents)
        }

        // Separate the file-metadata block ('what is in the file') from the section rows
        // ('what to restore'), matching the divider before the action area below.
        HorizontalDivider(modifier = Modifier.padding(horizontal = ChirpSpacing.Large))

        SectionOrder.forEach { section ->
            if (section in ready.contents.availableSections) {
                BackupSectionRow(
                    section = section,
                    count = ready.contents.countFor(section),
                    checked = section in ready.selection,
                    enabled = true,
                    onToggle = { onToggleSection(section) },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = ChirpSpacing.Large))

        Column(
            modifier = Modifier.padding(ChirpSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = ready.mode == BackupImportMode.MERGE,
                    onClick = { onSetMode(BackupImportMode.MERGE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.backup_import_mode_merge))
                }
                SegmentedButton(
                    selected = ready.mode == BackupImportMode.REPLACE,
                    onClick = { onSetMode(BackupImportMode.REPLACE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.backup_import_mode_replace))
                }
            }

            Text(
                text =
                    when (ready.mode) {
                        BackupImportMode.MERGE -> stringResource(R.string.backup_import_mode_merge_help)
                        BackupImportMode.REPLACE -> stringResource(R.string.backup_import_mode_replace_help)
                    },
                style = MaterialTheme.typography.bodySmall,
                // Informational guidance, not an error — REPLACE is confirmed in the dialog, so the
                // help body stays calm onSurfaceVariant like the MERGE branch.
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Small)) {
                Button(
                    onClick = onApply,
                    enabled = ready.selection.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.backup_import_button))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.backup_import_cancel))
                }
            }
        }
    }
}

@Composable
private fun BackupFileMetadata(contents: ChirpBackupContents) {
    contents.createdAtEpochMs?.let { createdAt ->
        val formatted =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(createdAt))
        Text(
            text = stringResource(R.string.backup_file_created, formatted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    contents.appVersion?.let { appVersion ->
        Text(
            text = stringResource(R.string.backup_file_app_version, appVersion),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportResultCard(
    summary: ChirpBackupManager.ImportSummary,
    onDone: () -> Unit,
) {
    SectionCard {
        Column(
            modifier = Modifier.padding(ChirpSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
        ) {
            Text(
                text =
                    if (summary.hasFailures) {
                        stringResource(R.string.backup_result_title_issues)
                    } else {
                        stringResource(R.string.backup_result_title)
                    },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )

            // Up to six label+detail pairs; thin dividers between them break the dense wall of
            // small text into scannable rows.
            Column {
                summary.results.forEachIndexed { index, result ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    Column(
                        modifier = Modifier.padding(vertical = ChirpSpacing.Small),
                    ) {
                        Text(
                            text = sectionLabel(result.section),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = sectionResultText(result),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (result.failure != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }

            // Clear gap so the action reads as separate from the result list, not stuck to it.
            Spacer(modifier = Modifier.height(ChirpSpacing.Medium))
            Button(onClick = onDone) {
                Text(stringResource(R.string.backup_result_done))
            }
        }
    }
}

@Composable
private fun sectionResultText(result: ChirpBackupManager.SectionResult): String =
    when {
        result.failure == ChirpBackupManager.SectionFailure.KEYS_REJECTED ->
            stringResource(R.string.backup_result_keys_rejected)

        // Settings apply one preference per commit, so "left unchanged" would overclaim.
        result.failure != null && result.section == BackupSection.SETTINGS ->
            stringResource(R.string.backup_result_settings_failed)

        result.failure != null -> stringResource(R.string.backup_result_section_failed)

        result.section == BackupSection.SETTINGS ->
            stringResource(R.string.backup_result_settings_applied, result.updated)

        result.section == BackupSection.API_KEYS ->
            pluralStringResource(R.plurals.backup_result_keys_restored, result.inserted, result.inserted)

        else ->
            stringResource(R.string.backup_result_section_counts, result.inserted, result.updated)
    }

// endregion

// region Shared pieces

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                // Outer gutter tracks the screen-gutter contract (not coincidentally Large).
                .padding(horizontal = ChirpSpacing.ScreenHorizontal),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column { content() }
    }
}

@Composable
private fun BackupSectionRow(
    section: BackupSection,
    count: Int,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    onValueChange = { onToggle() },
                    role = Role.Checkbox,
                    enabled = enabled,
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = ChirpSpacing.Large, vertical = ChirpSpacing.Small),
        // A null-callback Checkbox renders at its compact intrinsic size (no 48dp touch-target
        // reservation, since the row owns the toggle), so the gap to the label must be explicit.
        horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
        // Top-align so the checkbox + count pill anchor to the title's line; a two-line row
        // (e.g. API Keys with the "no keys" sub-line) no longer floats them to mid-height.
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            // Title and pill share the headline line so the pill tracks the title even when a
            // sub-line is present.
            Row(
                horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sectionLabel(section),
                    // House row scale: title = bodyLarge + Medium (onSurface).
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ChirpPill(label = count.toString())
            }
            if (section == BackupSection.API_KEYS && count == 0) {
                Text(
                    text = stringResource(R.string.backup_no_keys),
                    // House row scale: supporting line = bodyMedium (onSurfaceVariant).
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun sectionLabel(section: BackupSection): String =
    stringResource(
        when (section) {
            BackupSection.SETTINGS -> R.string.backup_section_settings
            BackupSection.TAGS -> R.string.backup_section_tags
            BackupSection.PROFILES -> R.string.backup_section_profiles
            BackupSection.WORD_REPLACEMENTS -> R.string.backup_section_word_replacements
            BackupSection.PROCESSING_PRESETS -> R.string.backup_section_processing_presets
            BackupSection.API_KEYS -> R.string.backup_section_api_keys
        },
    )

@Composable
private fun ProgressRow(text: String) {
    Row(
        modifier = Modifier.padding(ChirpSpacing.Large),
        horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusBanner(
    message: StatusMessage,
    onDismiss: () -> Unit,
) {
    val containerColor =
        when (message) {
            is StatusMessage.Success -> MaterialTheme.colorScheme.primaryContainer
            is StatusMessage.Error -> MaterialTheme.colorScheme.errorContainer
        }
    val contentColor =
        when (message) {
            is StatusMessage.Success -> MaterialTheme.colorScheme.onPrimaryContainer
            is StatusMessage.Error -> MaterialTheme.colorScheme.onErrorContainer
        }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ChirpSpacing.Large, vertical = ChirpSpacing.Small),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        onClick = onDismiss,
    ) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(ChirpSpacing.Large),
        )
    }
}

@Composable
private fun ImportConfirmDialog(
    mode: BackupImportMode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_confirm_title)) },
        text = {
            Text(
                text =
                    when (mode) {
                        BackupImportMode.MERGE -> stringResource(R.string.backup_confirm_merge_body)
                        BackupImportMode.REPLACE -> stringResource(R.string.backup_confirm_replace_body)
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.backup_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_import_cancel))
            }
        },
    )
}

/**
 * Passphrase prompt mirroring the LLM key-backup dialog: both modes enforce the shared
 * minimum length; export mode additionally requires a matching confirmation field.
 */
@Composable
private fun BackupPassphraseDialog(
    mode: PassphrasePromptMode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmPassphrase by rememberSaveable { mutableStateOf("") }
    val requiresConfirmation = mode == PassphrasePromptMode.EXPORT
    val canConfirm =
        passphrase.length >= MIN_BACKUP_PASSPHRASE_LENGTH &&
            (!requiresConfirmation || passphrase == confirmPassphrase)

    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text =
                    when (mode) {
                        PassphrasePromptMode.EXPORT -> stringResource(R.string.backup_passphrase_export_title)
                        PassphrasePromptMode.IMPORT -> stringResource(R.string.backup_passphrase_import_title)
                    },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text =
                        when (mode) {
                            PassphrasePromptMode.EXPORT -> stringResource(R.string.backup_passphrase_export_body)
                            PassphrasePromptMode.IMPORT -> stringResource(R.string.backup_passphrase_import_body)
                        },
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.backup_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (requiresConfirmation) {
                    OutlinedTextField(
                        value = confirmPassphrase,
                        onValueChange = { confirmPassphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.backup_passphrase_confirm_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = {
                            if (confirmPassphrase.isNotEmpty() && confirmPassphrase != passphrase) {
                                Text(stringResource(R.string.backup_passphrase_mismatch))
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.backup_passphrase_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_import_cancel))
            }
        },
    )
}

// endregion

/** Matches MIN_PASSPHRASE_LENGTH in the LLM key-backup flow (same CHIRPKEY container). */
private const val MIN_BACKUP_PASSPHRASE_LENGTH = 8
