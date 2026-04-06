package dev.chirpboard.app.feature.recording.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.ui.components.AudioWaveform
import dev.chirpboard.app.feature.recording.ui.components.formatTimeMs
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onNavigateBack: () -> Unit,
    onRecordingComplete: (recordingId: String) -> Unit = { onNavigateBack() },
    autoStart: Boolean = true,
    viewModel: RecordViewModel = hiltViewModel(),
) {
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val rawAmplitudeHistory by viewModel.amplitudeHistory.collectAsStateWithLifecycle()
    val amplitudeHistory = remember(rawAmplitudeHistory) { rawAmplitudeHistory.toImmutableList() }
    val lastCompletedRecordingId by viewModel.lastCompletedRecordingId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var elapsedMs by remember { mutableLongStateOf(0L) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showBackDialog by remember { mutableStateOf(false) }

    var hadRecordingSession by remember { mutableStateOf(false) }
    var pendingNavigateBack by remember { mutableStateOf(false) }

    val isRecording = recordingState is RecordingState.Recording || recordingState is RecordingState.Starting || recordingState is RecordingState.Stopping
    val isPaused = recordingState is RecordingState.Paused
    val isActive = isRecording || isPaused

    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Recording) {
            hadRecordingSession = true
        }
    }

    LaunchedEffect(autoStart) {
        if (autoStart && recordingState is RecordingState.Idle) {
            viewModel.startRecording(context)
        }
    }

    var previousSegmentsMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is RecordingState.Recording -> {
                val segmentStart = state.startTimeMs
                while (true) {
                    withFrameMillis {
                        elapsedMs = previousSegmentsMs + (System.currentTimeMillis() - segmentStart)
                    }
                }
            }
            is RecordingState.Paused -> {
                previousSegmentsMs = state.accumulatedMs
                elapsedMs = state.accumulatedMs
            }
            is RecordingState.Idle -> {
                previousSegmentsMs = 0L
            }
            else -> {}
        }
    }

    LaunchedEffect(lastCompletedRecordingId) {
        val recordingId = lastCompletedRecordingId
        if (recordingId != null) {
            delay(200)
            onRecordingComplete(recordingId.toString())
            viewModel.clearLastCompletedRecordingId()
        }
    }

    BackHandler(enabled = isActive) {
        showBackDialog = true
    }

    if (showCancelDialog) {
        AnimatedAlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.discard_recording_title)) },
            text = { Text(stringResource(R.string.discard_recording_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        viewModel.cancelRecording(context)
                        onNavigateBack()
                    },
                ) {
                    Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.keep_recording))
                }
            },
        )
    }

    if (showRestartDialog) {
        AnimatedAlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text(stringResource(R.string.start_over_title)) },
            text = { Text(stringResource(R.string.start_over_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartDialog = false
                        previousSegmentsMs = 0L
                        elapsedMs = 0L
                        viewModel.restartRecording(context)
                    },
                ) {
                    Text(stringResource(R.string.start_over), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text(stringResource(R.string.keep_recording))
                }
            },
        )
    }

    if (showBackDialog) {
        AnimatedAlertDialog(
            onDismissRequest = { showBackDialog = false },
            title = { Text(stringResource(R.string.recording_in_progress_title)) },
            text = { Text(stringResource(R.string.recording_in_progress_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackDialog = false
                        pendingNavigateBack = true
                        viewModel.stopRecording(context)
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBackDialog = false
                        viewModel.cancelRecording(context)
                        onNavigateBack()
                    },
                ) {
                    Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "glowTransition")
    val glowColor by infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.0f),
        targetValue = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowColor"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Recording") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isActive) {
                            showCancelDialog = true
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.desc_close))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = formatTimeMs(elapsedMs),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 72.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp
                ),
                color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isRecording) glowColor else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    AudioWaveform(
                        amplitudes = amplitudeHistory,
                        isActive = isRecording,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showRestartDialog = true },
                    enabled = isActive
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Over")
                }

                if (isPaused || !isActive) {
                    FilledTonalButton(
                        onClick = {
                            if (isActive) {
                                viewModel.resumeRecording(context)
                            } else {
                                viewModel.startRecording(context)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isActive) Icons.Default.PlayArrow else Icons.Default.Mic,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isActive) "Resume" else "Record")
                    }
                } else {
                    FilledTonalButton(
                        onClick = { viewModel.pauseRecording(context) }
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pause")
                    }
                }

                Button(
                    onClick = {
                        pendingNavigateBack = true
                        viewModel.stopRecording(context)
                    },
                    enabled = isActive,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Done", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
