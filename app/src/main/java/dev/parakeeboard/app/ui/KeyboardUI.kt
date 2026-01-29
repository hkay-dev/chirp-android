package dev.parakeeboard.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.parakeeboard.app.KeyboardState
import kotlinx.coroutines.flow.StateFlow

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    surface = Color(0xFF1C1B1F),
    onSurface = Color.White,
    error = Color(0xFFB3261E),
    onError = Color.White
)

private val LlmActiveColor = Color(0xFF4CAF50)
private val LlmInactiveColor = Color(0xFF666666)

@Composable
fun KeyboardUI(
    stateFlow: StateFlow<KeyboardState>,
    llmEnabled: Boolean,
    onTap: () -> Unit,
    onToggleLlm: () -> Unit
) {
    val state by stateFlow.collectAsState()

    MaterialTheme(colorScheme = DarkColors) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // LLM Toggle in top-right corner
                LlmToggle(
                    enabled = llmEnabled,
                    onClick = onToggleLlm,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )

                // Main content
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (val currentState = state) {
                        is KeyboardState.Idle -> IdleContent(onTap)
                        is KeyboardState.Recording -> RecordingContent(onTap)
                        is KeyboardState.Transcribing -> ProcessingContent("Transcribing...")
                        is KeyboardState.Polishing -> ProcessingContent("Polishing...")
                        is KeyboardState.Downloading -> DownloadingContent(currentState.progress)
                        is KeyboardState.ModelNotReady -> ModelNotReadyContent()
                        is KeyboardState.Error -> ErrorContent(currentState.message, onTap)
                        is KeyboardState.LlmError -> LlmErrorContent(currentState.message)
                    }
                }
            }
        }
    }
}

@Composable
private fun LlmToggle(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bgColor = if (enabled) LlmActiveColor else LlmInactiveColor
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor.copy(alpha = 0.3f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (enabled) "LLM ON" else "LLM OFF",
            color = if (enabled) LlmActiveColor else Color.Gray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun IdleContent(onTap: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(80.dp)
            .background(color, CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        MicIcon(color = Color.White)
    }
    Text(
        text = "Tap to speak",
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 16.dp)
    )
}

@Composable
private fun RecordingContent(onTap: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val color by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.error,
        label = "color"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .background(color, CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        StopIcon(color = Color.White)
    }
    Text(
        text = "Tap to stop",
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 16.dp)
    )
}

@Composable
private fun ProcessingContent(message: String) {
    CircularProgressIndicator(
        modifier = Modifier.size(80.dp),
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = 4.dp
    )
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 16.dp)
    )
}

@Composable
private fun LlmErrorContent(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun DownloadingContent(progress: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Downloading model... ${(progress * 100).toInt()}%",
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
private fun ModelNotReadyContent() {
    Text(
        text = "Model not ready\nOpen app to download",
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ErrorContent(message: String, onTap: () -> Unit) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clickable(onClick = onTap)
    )
    Text(
        text = "Tap to retry",
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun MicIcon(color: Color) {
    // Simple mic representation using a circle and stem
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(20.dp, 28.dp)
                .background(color, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(4.dp, 8.dp)
                .background(color)
        )
        Box(
            modifier = Modifier
                .size(24.dp, 4.dp)
                .background(color)
        )
    }
}

@Composable
private fun StopIcon(color: Color) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(color)
    )
}
