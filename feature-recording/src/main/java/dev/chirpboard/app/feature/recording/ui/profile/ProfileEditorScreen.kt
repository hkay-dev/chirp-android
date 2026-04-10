package dev.chirpboard.app.feature.recording.ui.profile
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.feature.recording.R

private val ProfileProcessingModeIds = listOf(null, "enhance", "summarize", "meeting_notes", "action_items")

@Composable
private fun profileProcessingModeLabel(modeId: String?): String =
    when (modeId) {
        null -> stringResource(R.string.rec_profile_mode_none)
        "enhance" -> stringResource(R.string.rec_profile_mode_enhance)
        "summarize" -> stringResource(R.string.rec_profile_mode_summarize)
        "meeting_notes" -> stringResource(R.string.rec_profile_mode_meeting_notes)
        "action_items" -> stringResource(R.string.rec_profile_mode_action_items)
        else -> stringResource(R.string.rec_profile_mode_none)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    viewModel: ProfileEditorViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate back when saved
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onSaved()
        }
    }

    // Show error messages
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short,
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (viewModel.isEditing) stringResource(R.string.rec_edit_profile) else stringResource(R.string.rec_new_profile),
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
                actions = {
                    IconButton(
                        onClick = { viewModel.save() },
                        enabled = !uiState.isLoading && uiState.name.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.desc_save),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        if (uiState.isLoading && viewModel.isEditing && uiState.name.isEmpty()) {
            // Loading state for edit mode
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Name field
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text(stringResource(R.string.rec_profile_name)) },
                    placeholder = { Text(stringResource(R.string.rec_profile_name_placeholder)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Next,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Icon field
                OutlinedTextField(
                    value = uiState.icon,
                    onValueChange = { viewModel.updateIcon(it) },
                    label = { Text(stringResource(R.string.rec_profile_icon)) },
                    placeholder = { Text(stringResource(R.string.rec_profile_icon_placeholder)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            imeAction = ImeAction.Next,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.rec_profile_icon_desc)) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.rec_profile_automation_settings),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Auto Transcribe toggle
                SettingToggle(
                    title = stringResource(R.string.rec_profile_auto_transcribe_title),
                    description = stringResource(R.string.rec_profile_auto_transcribe_description),
                    checked = uiState.autoTranscribe,
                    onCheckedChange = { viewModel.updateAutoTranscribe(it) },
                )

                // Auto Title toggle
                SettingToggle(
                    title = stringResource(R.string.rec_profile_auto_title_title),
                    description = stringResource(R.string.rec_profile_auto_title_description),
                    checked = uiState.autoTitle,
                    onCheckedChange = { viewModel.updateAutoTitle(it) },
                )

                // Auto Summary toggle
                SettingToggle(
                    title = stringResource(R.string.rec_profile_auto_summary_title),
                    description = stringResource(R.string.rec_profile_auto_summary_description),
                    checked = uiState.autoSummary,
                    onCheckedChange = { viewModel.updateAutoSummary(it) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.rec_profile_processing_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Processing mode dropdown
                ProcessingModeDropdown(
                    selectedMode = uiState.defaultProcessingMode,
                    onModeSelected = { viewModel.updateDefaultProcessingMode(it) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.rec_profile_obsidian_integration),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Auto Export to Obsidian toggle
                SettingToggle(
                    title = stringResource(R.string.rec_profile_auto_export_title),
                    description = stringResource(R.string.rec_profile_auto_export_description),
                    checked = uiState.autoExportToObsidian,
                    onCheckedChange = { viewModel.updateAutoExportToObsidian(it) },
                )

                // Obsidian vault path
                if (uiState.autoExportToObsidian) {
                    OutlinedTextField(
                        value = uiState.obsidianVaultPath,
                        onValueChange = { viewModel.updateObsidianVaultPath(it) },
                        label = { Text(stringResource(R.string.rec_profile_vault)) },
                        placeholder = { Text(stringResource(R.string.rec_profile_vault_desc)) },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                imeAction = ImeAction.Done,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.rec_profile_vault_support)) },
                    )
                }

                // Spacer for bottom padding
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null, // Handled by toggleable modifier on the parent
            )
        },
        modifier =
            modifier
                .semantics(mergeDescendants = true) {}
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    role = Role.Switch,
                ),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessingModeDropdown(
    selectedMode: String?,
    onModeSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = profileProcessingModeLabel(selectedMode),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.rec_profile_mode)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable, true),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ProfileProcessingModeIds.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(profileProcessingModeLabel(mode)) },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
