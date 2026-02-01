package dev.parakeeboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import dev.parakeeboard.app.db.AppDatabase
import dev.parakeeboard.app.db.Transcription
import dev.parakeeboard.app.download.ModelDownloader
import dev.parakeeboard.app.llm.ProcessingMode
import dev.parakeeboard.app.llm.ProcessingModeRepository
import dev.parakeeboard.app.llm.TextProcessor
import dev.parakeeboard.app.ui.theme.ParakeetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            100
        )

        setContent {
            ParakeetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
private fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(if (selectedTab == 0) Icons.Filled.History else Icons.Outlined.History, null) },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(if (selectedTab == 1) Icons.Filled.Edit else Icons.Outlined.Edit, null) },
                    label = { Text("Notes") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(if (selectedTab == 2) Icons.Filled.Settings else Icons.Outlined.Settings, null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> HistoryScreen()
                1 -> NotesScreen(db)
                2 -> SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val transcriptions by db.transcriptionDao().getAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val textProcessor = remember { TextProcessor() }
    val modeRepository = remember { ProcessingModeRepository(context) }
    val currentMode by modeRepository.currentMode.collectAsState(initial = ProcessingMode.Formal)
    var reprocessingId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("History") }
            )
        }
    ) { paddingValues ->
        if (transcriptions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No transcriptions yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(transcriptions, key = { it.id }) { transcription ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                scope.launch(Dispatchers.IO) {
                                    db.transcriptionDao().delete(transcription)
                                }
                                true
                            } else false
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        enableDismissFromStartToEnd = false
                    ) {
                        TranscriptionCard(
                            transcription = transcription,
                            isReprocessing = reprocessingId == transcription.id,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = transcription.processedText ?: transcription.rawText
                                clipboard.setPrimaryClip(ClipData.newPlainText("transcription", text))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            },
                            onReprocess = {
                                if (reprocessingId == null) {
                                    reprocessingId = transcription.id
                                    scope.launch {
                                        // Use current mode
                                        val mode = currentMode
                                        val result = textProcessor.process(transcription.rawText, mode)
                                        result.onSuccess { polished ->
                                            withContext(Dispatchers.IO) {
                                                db.transcriptionDao().update(
                                                    transcription.copy(processedText = polished)
                                                )
                                            }
                                        }
                                        reprocessingId = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptionCard(
    transcription: Transcription,
    isReprocessing: Boolean,
    onCopy: () -> Unit,
    onReprocess: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onCopy,
                onLongClick = onReprocess
            ),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormat.format(Date(transcription.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isReprocessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else if (transcription.processedText != null) {
                    SuggestionChip(
                        onClick = { },
                        label = { Text("LLM", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp),
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    )
                }
            }

            Text(
                text = transcription.processedText ?: transcription.rawText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (transcription.processedText != null && transcription.rawText != transcription.processedText) {
                Text(
                    text = "Raw: ${transcription.rawText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val downloader = remember { ModelDownloader(context) }

    var isModelDownloaded by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var isModelLoading by remember { mutableStateOf(false) }
    var isModelReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isModelDownloaded = downloader.isModelDownloaded()
        if (isModelDownloaded) {
            isModelLoading = true
            val recognizer = SherpaRecognizer(context)
            isModelReady = recognizer.initialize()
            recognizer.release()
            isModelLoading = false
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Parakeet Keyboard") },
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
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = "Offline voice-to-text input",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                SetupStepCard(
                    number = 1,
                    title = "Download Model",
                    description = if (!isModelDownloaded) "~660 MB Parakeet model" else "Model downloaded",
                    isComplete = isModelDownloaded,
                    isInProgress = isDownloading,
                    progress = downloadProgress,
                    error = downloadError,
                    buttonText = if (isDownloading) "Downloading..." else "Download",
                    onAction = {
                        if (!isDownloading && !isModelDownloaded) {
                            isDownloading = true
                            downloadError = null
                        }
                    }
                )
            }

            if (isDownloading) {
                item {
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
            }

            item {
                SetupStepCard(
                    number = 2,
                    title = "Enable Keyboard",
                    description = "Enable in system settings",
                    isComplete = isKeyboardEnabled(context),
                    buttonText = "Enable",
                    onAction = {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                )
            }

            item {
                SetupStepCard(
                    number = 3,
                    title = "Select Keyboard",
                    description = "Choose Parakeet as input",
                    isComplete = isKeyboardSelected(context),
                    buttonText = "Select",
                    onAction = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    }
                )
            }

            item {
                ModelStatusIndicator(
                    isLoading = isModelLoading,
                    isReady = isModelReady
                )
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
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Step indicator circle
            Surface(
                modifier = Modifier.size(40.dp),
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
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text = number.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Content column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isInProgress) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
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
private fun ModelStatusIndicator(isLoading: Boolean, isReady: Boolean) {
    AnimatedContent(
        targetState = when {
            isLoading -> "loading"
            isReady -> "ready"
            else -> "idle"
        },
        label = "status"
    ) { state ->
        when (state) {
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
            "ready" -> Row(
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
        }
    }
}

private fun isKeyboardEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabledMethods = imm.enabledInputMethodList
    return enabledMethods.any { it.packageName == context.packageName }
}

private fun isKeyboardSelected(context: Context): Boolean {
    val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return defaultIme?.contains(context.packageName) == true
}
