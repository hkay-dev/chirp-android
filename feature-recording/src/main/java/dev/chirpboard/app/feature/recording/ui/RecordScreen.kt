package dev.chirpboard.app.feature.recording.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.withFrameMillis
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.feature.recording.ui.components.AudioWaveform
import dev.chirpboard.app.feature.recording.ui.components.MainActionButton
import dev.chirpboard.app.feature.recording.ui.components.RecordingGlowBackground
import dev.chirpboard.app.feature.recording.ui.components.RecordingUiState
import dev.chirpboard.app.feature.recording.ui.components.SecondaryButtonsRow
import dev.chirpboard.app.feature.recording.ui.components.formatTimeMs
import kotlinx.coroutines.delay

/**
 * Full-screen recording interface with real-time audio visualization.
 *
 * Features:
 * - Real-time waveform visualization
 * - Large timer display
 * - Background glow effects based on state
 * - Main action button (Stop when recording)
 * - Secondary controls (Done, Cancel, Restart)
 *
 * Button behaviors:
 * - Main button (during recording): STOP recording and save
 * - Done (checkmark): STOP recording and save, navigate back
 * - Cancel (X): Discard recording (with confirmation), navigate back
 * - Restart (refresh): Discard current recording and start fresh
 *
 * @param onNavigateBack Called when user exits the screen (cancel/discard)
 * @param onRecordingComplete Called with the recording ID when a recording is saved successfully
 * @param autoStart Whether to automatically start recording on screen entry
 * @param viewModel RecordViewModel instance
 */
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

    // Track elapsed time for timer display
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showBackDialog by remember { mutableStateOf(false) }

    // Track if we had a recording session (to know when to navigate back)
    var hadRecordingSession by remember { mutableStateOf(false) }
    // Track if user explicitly requested navigation back after save
    var pendingNavigateBack by remember { mutableStateOf(false) }

    // Derive UI state from core recording state
    val uiState =
        when (recordingState) {
            is RecordingState.Recording -> RecordingUiState.Recording

            is RecordingState.Starting -> RecordingUiState.Recording

            // Treat starting as recording for UI
            is RecordingState.Paused -> RecordingUiState.Paused

            is RecordingState.Stopping -> RecordingUiState.Recording

            // Keep showing recording UI while stopping
            else -> RecordingUiState.Idle
        }

    val isRecording = uiState is RecordingUiState.Recording
    val isPaused = uiState is RecordingUiState.Paused
    val isActive = isRecording || isPaused

    // Mark that we've had a recording session when recording starts
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Recording) {
            hadRecordingSession = true
        }
    }

    // Auto-start recording when screen opens
    LaunchedEffect(autoStart) {
        if (autoStart && recordingState is RecordingState.Idle) {
            viewModel.startRecording()
        }
    }

    // Track accumulated time from previous recording segments (across pause/resume cycles)
    var previousSegmentsMs by remember { mutableLongStateOf(0L) }

    // Timer update loop — accounts for pause/resume
    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is RecordingState.Recording -> {
                val segmentStart = state.startTimeMs
                while (true) {
                    withFrameMillis { frameTime ->
                        elapsedMs = previousSegmentsMs + (System.currentTimeMillis() - segmentStart)
                    }
                }
            }

            is RecordingState.Paused -> {
                // Freeze timer at accumulated time; store it for next resume
                previousSegmentsMs = state.accumulatedMs
                elapsedMs = state.accumulatedMs
            }

            is RecordingState.Idle -> {
                // Reset for next recording session
                previousSegmentsMs = 0L
            }

            else -> {
                // Starting, Stopping, Error — keep showing current elapsed
            }
        }
    }

    // Navigate to recording detail after recording completes.
    // lastCompletedRecordingId becoming non-null is the definitive signal that a recording
    // was saved (via Done, back-gesture Save, or notification Stop action).
    LaunchedEffect(lastCompletedRecordingId) {
        val recordingId = lastCompletedRecordingId
        if (recordingId != null) {
            viewModel.clearLastCompletedRecordingId()
            delay(200) // Brief delay for state to settle
            onRecordingComplete(recordingId.toString())
        }
    }

    // Handle back gesture
    BackHandler(enabled = isActive) {
        showBackDialog = true
    }

    // Cancel confirmation dialog
    if (showCancelDialog) {
        AnimatedAlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Discard Recording?") },
            text = { Text("This recording will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        viewModel.cancelRecording()
                        onNavigateBack()
                    },
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Keep Recording")
                }
            },
        )
    }

    // Restart confirmation dialog
    if (showRestartDialog) {
        AnimatedAlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Start Over?") },
            text = { Text("Current recording will be discarded.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartDialog = false
                        previousSegmentsMs = 0L
                        elapsedMs = 0L
                        viewModel.restartRecording()
                    },
                ) {
                    Text("Start Over", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("Keep Recording")
                }
            },
        )
    }

    // Back gesture dialog (during recording)
    if (showBackDialog) {
        AnimatedAlertDialog(
            onDismissRequest = { showBackDialog = false },
            title = { Text("Recording in Progress") },
            text = { Text("Would you like to save or discard this recording?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackDialog = false
                        pendingNavigateBack = true
                        viewModel.stopRecording()
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBackDialog = false
                        viewModel.cancelRecording()
                        onNavigateBack()
                    },
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    // Animate timer color between recording states
    val timerColor by animateColorAsState(
        targetValue =
            when {
                isRecording -> MaterialTheme.colorScheme.error
                isPaused -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "timer_color",
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background glow layer
            RecordingGlowBackground(
                isRecording = isRecording,
                isPaused = isPaused,
                modifier = Modifier.fillMaxSize(),
            )

            // Close button — subtle, top-start, semi-transparent
            IconButton(
                onClick = {
                    if (isActive) {
                        showBackDialog = true
                    } else {
                        onNavigateBack()
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .systemBarsPadding()
                        .padding(8.dp)
                        .size(48.dp),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    modifier = Modifier.size(24.dp),
                )
            }

            // Main content
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(modifier = Modifier.weight(1f))

                // Timer display
                Text(
                    text = formatTimeMs(elapsedMs),
                    style =
                        MaterialTheme.typography.displayLarge.copy(
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Light,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                        ),
                    color = timerColor,
                )

                // Status text with animated color
                val statusTextColor by animateColorAsState(
                    targetValue =
                        when {
                            recordingState is RecordingState.Recording -> MaterialTheme.colorScheme.error
                            recordingState is RecordingState.Paused -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    animationSpec = tween(400, easing = FastOutSlowInEasing),
                    label = "statusTextColor",
                )

                Crossfade(
                    targetState =
                        when {
                            recordingState is RecordingState.Recording -> "Recording"
                            recordingState is RecordingState.Starting -> "Starting..."
                            recordingState is RecordingState.Paused -> "Paused"
                            recordingState is RecordingState.Stopping -> "Saving..."
                            hadRecordingSession -> "Saved"
                            else -> "Ready"
                        },
                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                    label = "statusText",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = statusTextColor,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Audio waveform
                AudioWaveform(
                    amplitudes = amplitudeHistory,
                    isActive = isRecording,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Spacer(modifier = Modifier.height(48.dp))

                // Main action button
                // When recording: Shows STOP icon, stops and saves
                // When idle: Shows MIC icon, starts recording
                MainActionButton(
                    state = uiState,
                    recordingColor = MaterialTheme.colorScheme.error,
                    onStartRecording = { viewModel.startRecording() },
                    onPause = { viewModel.pauseRecording() },
                    onResume = { viewModel.resumeRecording() },
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Secondary buttons (visible when recording)
                AnimatedVisibility(
                    visible = isActive,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
                    exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 },
                ) {
                    SecondaryButtonsRow(
                        onDone = {
                            // Done = stop, save, and navigate to transcription
                            pendingNavigateBack = true
                            viewModel.stopRecording()
                        },
                        onCancel = {
                            // Cancel = show confirmation dialog
                            showCancelDialog = true
                        },
                        onRestart = {
                            // Restart = show confirmation, then discard and start fresh
                            showRestartDialog = true
                        },
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
