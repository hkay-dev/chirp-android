package dev.parakeeboard.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.parakeeboard.app.download.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * New settings screen with navigation to sub-settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToLlmSettings: () -> Unit,
    onNavigateToObsidianSettings: () -> Unit,
    onNavigateToKeyboardSettings: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToTags: () -> Unit,
    onNavigateToWordReplacements: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val context = LocalContext.current
    val downloader = remember { ModelDownloader(context) }

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

    // Check states on launch
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

            // Configuration Section Header
            item {
                Text(
                    text = "Configuration",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            // LLM Settings
            item {
                SettingsNavigationItem(
                    icon = Icons.Default.Cloud,
                    title = "LLM Settings",
                    subtitle = "Configure Gemini API for text processing",
                    onClick = onNavigateToLlmSettings
                )
            }

            // Obsidian Settings
            item {
                SettingsNavigationItem(
                    icon = Icons.Default.Code,
                    title = "Obsidian Settings",
                    subtitle = "Configure vault and auto-export",
                    onClick = onNavigateToObsidianSettings
                )
            }

            // Keyboard Settings
            item {
                SettingsNavigationItem(
                    icon = Icons.Default.Keyboard,
                    title = "Keyboard Settings",
                    subtitle = "Microphone gain and haptics",
                    onClick = onNavigateToKeyboardSettings
                )
            }

            // Data Management Section Header
            item {
                Text(
                    text = "Data Management",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            // Profiles
            item {
                SettingsNavigationItem(
                    icon = Icons.Default.Person,
                    title = "Profiles",
                    subtitle = "Manage recording profiles",
                    onClick = onNavigateToProfiles
                )
            }

            // Tags
            item {
                SettingsNavigationItem(
                    icon = Icons.AutoMirrored.Filled.Label,
                    title = "Tags",
                    subtitle = "Manage tags for recordings",
                    onClick = onNavigateToTags
                )
            }

            // Word Replacements
            item {
                SettingsNavigationItem(
                    icon = Icons.Default.SwapHoriz,
                    title = "Word Replacements",
                    subtitle = "Auto-correct transcription mistakes",
                    onClick = onNavigateToWordReplacements
                )
            }

            // About Section Header
            item {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            // About
            item {
                SettingsNavigationItem(
                    icon = Icons.Default.Info,
                    title = "About",
                    subtitle = "App version and information",
                    onClick = onNavigateToAbout
                )
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingsNavigationItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Navigate",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

private fun checkKeyboardEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabledMethods = imm.enabledInputMethodList
    return enabledMethods.any { it.packageName == context.packageName }
}

private fun checkKeyboardSelected(context: Context): Boolean {
    val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return defaultIme?.contains(context.packageName) == true
}
