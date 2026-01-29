package dev.parakeeboard.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import dev.parakeeboard.app.download.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        // Request mic permission upfront
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
                    SetupScreen()
                }
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

        // Step 1: Download model
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

        // Handle download in LaunchedEffect
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
                            // Load model
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

        // Step 2: Enable keyboard
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

        // Step 3: Select keyboard
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

        // Model status
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
