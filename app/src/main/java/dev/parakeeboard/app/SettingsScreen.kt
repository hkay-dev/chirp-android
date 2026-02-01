package dev.parakeeboard.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.parakeeboard.app.download.ModelDownloader
import dev.parakeeboard.app.llm.ProcessingMode
import dev.parakeeboard.app.llm.ProcessingModeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val downloader = remember { ModelDownloader(context) }
    val modeRepository = remember { ProcessingModeRepository(context) }
    val prefs = remember { Preferences(context) }
    val scope = rememberCoroutineScope()

    // Model state
    var isModelDownloaded by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var isModelLoading by remember { mutableStateOf(false) }
    var isModelReady by remember { mutableStateOf(false) }

    // Keyboard state
    var isKeyboardEnabled by remember { mutableStateOf(false) }
    var isKeyboardSelected by remember { mutableStateOf(false) }

    // Setup section expansion state
    val allSetupComplete = isModelDownloaded && isKeyboardEnabled && isKeyboardSelected
    var isSetupExpanded by remember { mutableStateOf(!allSetupComplete) }

    // Check states on launch and when resuming
    LaunchedEffect(Unit) {
        isModelDownloaded = downloader.isModelDownloaded()
        isKeyboardEnabled = checkKeyboardEnabled(context)
        isKeyboardSelected = checkKeyboardSelected(context)

        if (isModelDownloaded) {
            isModelLoading = true
            val recognizer = SherpaRecognizer(context)
            isModelReady = recognizer.initialize()
            recognizer.release()
            isModelLoading = false
        }
    }

    // Handle download
    if (isDownloading) {
        LaunchedEffect(Unit) {
            downloader.downloadModel().collect { state ->
                when (state) {
                    is ModelDownloader.DownloadState.Progress -> {
                        downloadProgress = state.bytesDownloaded.toFloat() / state.totalBytes
                    }
                    is ModelDownloader.DownloadState.Complete -> {
                        isDownloading = false
                        isModelDownloaded = true
                        isModelLoading = true
                        withContext(Dispatchers.Default) {
                            val recognizer = SherpaRecognizer(context)
                            isModelReady = recognizer.initialize()
                            recognizer.release()
                        }
                        isModelLoading = false
                    }
                    is ModelDownloader.DownloadState.Error -> {
                        isDownloading = false
                        downloadError = state.message
                    }
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Setup Section
            item {
                SetupSection(
                    isExpanded = isSetupExpanded,
                    onToggleExpand = { isSetupExpanded = !isSetupExpanded },
                    isModelDownloaded = isModelDownloaded,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress,
                    downloadError = downloadError,
                    isKeyboardEnabled = isKeyboardEnabled,
                    isKeyboardSelected = isKeyboardSelected,
                    onDownloadClick = {
                        if (!isDownloading && !isModelDownloaded) {
                            isDownloading = true
                            downloadError = null
                        }
                    },
                    onEnableKeyboardClick = {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onSelectKeyboardClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    }
                )
            }

            // Model Status Section
            item {
                ModelStatusSection(
                    isModelDownloaded = isModelDownloaded,
                    isModelLoading = isModelLoading,
                    isModelReady = isModelReady
                )
            }

            // Processing Section
            item {
                ProcessingSection(
                    modeRepository = modeRepository,
                    scope = scope
                )
            }

            // Microphone Settings Section
            item {
                MicrophoneSettingsSection(
                    prefs = prefs
                )
            }
        }
    }
}

@Composable
private fun SetupSection(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    isModelDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadError: String?,
    isKeyboardEnabled: Boolean,
    isKeyboardSelected: Boolean,
    onDownloadClick: () -> Unit,
    onEnableKeyboardClick: () -> Unit,
    onSelectKeyboardClick: () -> Unit
) {
    val completedSteps = listOf(isModelDownloaded, isKeyboardEnabled, isKeyboardSelected).count { it }
    val totalSteps = 3
    val allComplete = completedSteps == totalSteps

    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "arrow_rotation"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Setup",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (allComplete) "All steps complete" else "$completedSteps/$totalSteps complete",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (allComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (allComplete) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Complete",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.rotate(rotationAngle),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expandable content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SetupStepCard(
                        number = 1,
                        title = "Download Model",
                        description = if (!isModelDownloaded) "~660 MB Parakeet model" else "Model downloaded",
                        isComplete = isModelDownloaded,
                        isInProgress = isDownloading,
                        progress = downloadProgress,
                        error = downloadError,
                        buttonText = if (isDownloading) "Downloading..." else "Download",
                        onAction = onDownloadClick
                    )

                    SetupStepCard(
                        number = 2,
                        title = "Enable Keyboard",
                        description = "Enable in system settings",
                        isComplete = isKeyboardEnabled,
                        buttonText = "Enable",
                        onAction = onEnableKeyboardClick
                    )

                    SetupStepCard(
                        number = 3,
                        title = "Select Keyboard",
                        description = "Choose Parakeet as input",
                        isComplete = isKeyboardSelected,
                        buttonText = "Select",
                        onAction = onSelectKeyboardClick
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    number: Int,
    title: String,
    description: String,
    isComplete: Boolean,
    isInProgress: Boolean = false,
    progress: Float = 0f,
    error: String? = null,
    buttonText: String,
    onAction: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Step indicator circle
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = when {
                    isComplete -> MaterialTheme.colorScheme.primaryContainer
                    isInProgress -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isComplete) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Complete",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = number.toString(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Content column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isInProgress) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Action button
            if (!isComplete && !isInProgress) {
                FilledTonalButton(onClick = onAction) {
                    Text(buttonText)
                }
            }
        }
    }
}

@Composable
private fun ModelStatusSection(
    isModelDownloaded: Boolean,
    isModelLoading: Boolean,
    isModelReady: Boolean
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Model Status",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            AnimatedContent(
                targetState = when {
                    !isModelDownloaded -> "not_downloaded"
                    isModelLoading -> "loading"
                    isModelReady -> "ready"
                    else -> "error"
                },
                label = "model_status"
            ) { state ->
                when (state) {
                    "not_downloaded" -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Model not downloaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    "loading" -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Loading model...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    "ready" -> Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Model ready",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Parakeet TDT 0.6B CTC",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    "error" -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Failed to load model",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingSection(
    modeRepository: ProcessingModeRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val currentMode by modeRepository.currentMode.collectAsState(initial = null)
    val customPrompt by modeRepository.customPrompt.collectAsState(initial = "")
    var editingPrompt by remember { mutableStateOf("") }
    var isEditingCustom by remember { mutableStateOf(false) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var isPromptExpanded by remember { mutableStateOf(false) }

    // Sync editing prompt when custom prompt changes
    LaunchedEffect(customPrompt) {
        if (!isEditingCustom) {
            editingPrompt = customPrompt
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Processing Mode",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Choose how transcriptions are processed by the LLM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Mode selector dropdown
            Box {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isDropdownExpanded = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentMode?.displayName ?: "Loading...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Select mode"
                        )
                    }
                }
                
                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Proofread") },
                        onClick = {
                            scope.launch { modeRepository.setMode(ProcessingMode.Proofread) }
                            isDropdownExpanded = false
                        },
                        trailingIcon = if (currentMode is ProcessingMode.Proofread) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else null
                    )
                    DropdownMenuItem(
                        text = { Text("Formal") },
                        onClick = {
                            scope.launch { modeRepository.setMode(ProcessingMode.Formal) }
                            isDropdownExpanded = false
                        },
                        trailingIcon = if (currentMode is ProcessingMode.Formal) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else null
                    )
                    DropdownMenuItem(
                        text = { Text("Casual") },
                        onClick = {
                            scope.launch { modeRepository.setMode(ProcessingMode.Casual) }
                            isDropdownExpanded = false
                        },
                        trailingIcon = if (currentMode is ProcessingMode.Casual) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else null
                    )
                    DropdownMenuItem(
                        text = { Text("Email") },
                        onClick = {
                            scope.launch { modeRepository.setMode(ProcessingMode.Email) }
                            isDropdownExpanded = false
                        },
                        trailingIcon = if (currentMode is ProcessingMode.Email) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else null
                    )
                    DropdownMenuItem(
                        text = { Text("Code") },
                        onClick = {
                            scope.launch { modeRepository.setMode(ProcessingMode.Code) }
                            isDropdownExpanded = false
                        },
                        trailingIcon = if (currentMode is ProcessingMode.Code) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else null
                    )
                    DropdownMenuItem(
                        text = { Text("Smart") },
                        onClick = {
                            scope.launch { modeRepository.setMode(ProcessingMode.Smart) }
                            isDropdownExpanded = false
                        },
                        trailingIcon = if (currentMode is ProcessingMode.Smart) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else null
                    )
                    DropdownMenuItem(
                        text = { Text("Custom") },
                        onClick = {
                            scope.launch {
                                modeRepository.setCustomPrompt(editingPrompt.ifBlank { "Clean up this transcript." })
                            }
                            isDropdownExpanded = false
                        },
                        trailingIcon = if (currentMode is ProcessingMode.Custom) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else null
                    )
                }
            }

            // Mode description
            val modeDescription = when (currentMode) {
                is ProcessingMode.Proofread -> "Basic proofreading: fix typos, punctuation, grammar while preserving your natural voice."
                is ProcessingMode.Formal -> "Professional tone with proper grammar and punctuation."
                is ProcessingMode.Casual -> "Light cleanup while keeping natural conversational tone."
                is ProcessingMode.Email -> "Formats as a professional email with greeting and closing."
                is ProcessingMode.Code -> "Preserves technical terms and syntax exactly."
                is ProcessingMode.Smart -> "Auto-detects content type (email, code, or formal)."
                is ProcessingMode.Custom -> "Uses your custom prompt below."
                null -> "Loading..."
            }
            Text(
                text = modeDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Prompt preview section (for non-Smart modes)
            if (currentMode != null && currentMode !is ProcessingMode.Smart) {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isPromptExpanded = !isPromptExpanded }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "System Prompt",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val rotationAngle by animateFloatAsState(
                                targetValue = if (isPromptExpanded) 180f else 0f,
                                label = "arrow rotation"
                            )
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (isPromptExpanded) "Collapse" else "Expand",
                                modifier = Modifier.rotate(rotationAngle)
                            )
                        }

                        AnimatedVisibility(
                            visible = isPromptExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Duplicate button (only for built-in modes)
                                if (currentMode !is ProcessingMode.Custom) {
                                    FilledTonalButton(
                                        onClick = {
                                            currentMode?.prompt?.let { prompt ->
                                                editingPrompt = prompt
                                                scope.launch {
                                                    modeRepository.setCustomPrompt(prompt)
                                                }
                                            }
                                        },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Text("Duplicate & Edit")
                                    }
                                }

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = currentMode?.prompt ?: "No prompt available",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(12.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Custom prompt editor (shown when Custom is selected or for editing)
            AnimatedVisibility(
                visible = currentMode is ProcessingMode.Custom,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Custom Prompt",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = editingPrompt,
                        onValueChange = { 
                            editingPrompt = it
                            isEditingCustom = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter your custom processing instructions...") },
                        minLines = 3,
                        maxLines = 5,
                        shape = MaterialTheme.shapes.medium
                    )
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                modeRepository.setCustomPrompt(editingPrompt)
                                isEditingCustom = false
                            }
                        },
                        enabled = editingPrompt != customPrompt && editingPrompt.isNotBlank(),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Save Prompt")
                    }
                }
            }
        }
    }
}

private fun checkKeyboardEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabledMethods = imm.enabledInputMethodList
    return enabledMethods.any { it.packageName == context.packageName }
}

private fun checkKeyboardSelected(context: Context): Boolean {
    val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return defaultIme?.contains(context.packageName) == true
}



@Composable
private fun MicrophoneSettingsSection(prefs: Preferences) {
    var gain by remember { mutableFloatStateOf(prefs.microphoneGain) }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Microphone Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(Modifier.height(12.dp))
            
            Text(
                "Microphone Gain: ${String.format("%.1f", gain)}x",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Slider(
                value = gain,
                onValueChange = { 
                    gain = it
                    prefs.microphoneGain = it
                },
                valueRange = 1.0f..5.0f,
                steps = 39,  // 0.1 increments from 1.0 to 5.0
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            Text(
                "Boost microphone volume for quieter environments. Values above 2.0x may introduce distortion.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
