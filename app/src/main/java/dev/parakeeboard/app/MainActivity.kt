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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import dev.parakeeboard.app.db.AppDatabase
import dev.parakeeboard.app.db.Transcription
import dev.parakeeboard.app.download.ModelDownloader
import dev.parakeeboard.app.llm.TextProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    surface = Color(0xFF1C1B1F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            100
        )

        setContent {
            MaterialTheme(colorScheme = DarkColors) {
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
    val tabs = listOf("Setup", "History")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> SetupScreen()
            1 -> HistoryScreen()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val transcriptions by db.transcriptionDao().getAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val textProcessor = remember { TextProcessor() }
    var reprocessingId by remember { mutableStateOf<Long?>(null) }

    if (transcriptions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    val result = textProcessor.process(transcription.rawText)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptionCard(
    transcription: Transcription,
    isReprocessing: Boolean,
    onCopy: () -> Unit,
    onReprocess: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onCopy,
                onLongClick = onReprocess
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dateFormat.format(Date(transcription.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isReprocessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(12.dp),
                        strokeWidth = 2.dp
                    )
                } else if (transcription.processedText != null) {
                    Text(
                        text = "LLM",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = transcription.processedText ?: transcription.rawText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (transcription.processedText != null && transcription.rawText != transcription.processedText) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Raw: ${transcription.rawText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp
                )
            }
        }
    }
}

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Parakeet Keyboard",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Offline voice-to-text input",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        SetupStep(
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

        Spacer(modifier = Modifier.height(24.dp))

        SetupStep(
            number = 2,
            title = "Enable Keyboard",
            description = "Enable in system settings",
            isComplete = isKeyboardEnabled(context),
            buttonText = "Enable",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SetupStep(
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

        Spacer(modifier = Modifier.height(48.dp))

        if (isModelLoading) {
            CircularProgressIndicator()
            Text(
                text = "Loading model...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else if (isModelReady) {
            Text(
                text = "Model ready!",
                color = Color(0xFF4CAF50),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun SetupStep(
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$number. $title",
            style = MaterialTheme.typography.titleMedium,
            color = if (isComplete) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (isInProgress) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (!isComplete && !isInProgress) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(buttonText)
            }
        } else if (isComplete) {
            Text(
                text = "Done",
                color = Color(0xFF4CAF50),
                modifier = Modifier.padding(top = 8.dp)
            )
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
