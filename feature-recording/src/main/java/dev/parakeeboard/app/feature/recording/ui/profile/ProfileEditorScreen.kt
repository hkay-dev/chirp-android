package dev.parakeeboard.app.feature.recording.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    viewModel: ProfileEditorViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
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
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) "Edit Profile" else "New Profile") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.save() },
                        enabled = !uiState.isLoading && uiState.name.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (uiState.isLoading && viewModel.isEditing && uiState.name.isEmpty()) {
            // Loading state for edit mode
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Name field
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text("Name *") },
                    placeholder = { Text("e.g., Meeting Notes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Icon field
                OutlinedTextField(
                    value = uiState.icon,
                    onValueChange = { viewModel.updateIcon(it) },
                    label = { Text("Icon (emoji)") },
                    placeholder = { Text("e.g., \uD83C\uDFA4") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Optional emoji to identify this profile") }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    text = "Automation Settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // Auto Transcribe toggle
                SettingToggle(
                    title = "Auto Transcribe",
                    description = "Automatically transcribe recordings when finished",
                    checked = uiState.autoTranscribe,
                    onCheckedChange = { viewModel.updateAutoTranscribe(it) }
                )
                
                // Auto Title toggle
                SettingToggle(
                    title = "Auto Title",
                    description = "Generate title using AI after transcription",
                    checked = uiState.autoTitle,
                    onCheckedChange = { viewModel.updateAutoTitle(it) }
                )
                
                // Auto Summary toggle
                SettingToggle(
                    title = "Auto Summary",
                    description = "Generate summary using AI after transcription",
                    checked = uiState.autoSummary,
                    onCheckedChange = { viewModel.updateAutoSummary(it) }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    text = "Processing",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // Processing mode dropdown
                ProcessingModeDropdown(
                    selectedMode = uiState.defaultProcessingMode,
                    onModeSelected = { viewModel.updateDefaultProcessingMode(it) }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    text = "Obsidian Integration",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // Auto Export to Obsidian toggle
                SettingToggle(
                    title = "Auto Export to Obsidian",
                    description = "Automatically export processed recordings to Obsidian",
                    checked = uiState.autoExportToObsidian,
                    onCheckedChange = { viewModel.updateAutoExportToObsidian(it) }
                )
                
                // Obsidian vault path
                if (uiState.autoExportToObsidian) {
                    OutlinedTextField(
                        value = uiState.obsidianVaultPath,
                        onValueChange = { viewModel.updateObsidianVaultPath(it) },
                        label = { Text("Obsidian Vault Path") },
                        placeholder = { Text("Leave empty to use global setting") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Override the global Obsidian vault path for this profile") }
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessingModeDropdown(
    selectedMode: String?,
    onModeSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    // TODO: These should come from a central place or API
    val processingModes = listOf(
        null to "None (No processing)",
        "enhance" to "Enhance",
        "summarize" to "Summarize",
        "meeting_notes" to "Meeting Notes",
        "action_items" to "Action Items"
    )
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = processingModes.find { it.first == selectedMode }?.second ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text("Default Processing Mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            processingModes.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
