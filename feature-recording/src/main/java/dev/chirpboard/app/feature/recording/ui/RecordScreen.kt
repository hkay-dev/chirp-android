package dev.chirpboard.app.feature.recording.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chirpboard.app.core.recording.RecordingState
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
 * @param onNavigateBack Called when user exits the screen
 * @param autoStart Whether to automatically start recording on screen entry
 * @param viewModel RecordViewModel instance
 */
@Composable
fun RecordScreen(
    onNavigateBack: () -> Unit,
    autoStart: Boolean = true,
    viewModel: RecordViewModel = hiltViewModel()
) {
    val recordingState by viewModel.recordingState.collectAsState()
    val amplitudeHistory by viewModel.amplitudeHistory.collectAsState()
    
    // Track elapsed time for timer display
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showBackDialog by remember { mutableStateOf(false) }
    
    // Track if we had a recording session (to know when to navigate back)
    var hadRecordingSession by remember { mutableStateOf(false) }
    // Track if user explicitly requested navigation back after save
    var pendingNavigateBack by remember { mutableStateOf(false) }
    
    // Derive UI state from core recording state
    val uiState = when (recordingState) {
        is RecordingState.Recording -> RecordingUiState.Recording
        is RecordingState.Starting -> RecordingUiState.Recording // Treat starting as recording for UI
        is RecordingState.Stopping -> RecordingUiState.Recording // Keep showing recording UI while stopping
        else -> RecordingUiState.Idle
    }
    
    val isRecording = uiState is RecordingUiState.Recording
    val isActive = isRecording // For now, no pause state - just recording or idle
    
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
    
    // Timer update loop
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Recording) {
            val startTime = (recordingState as RecordingState.Recording).startTimeMs
            while (true) {
                elapsedMs = System.currentTimeMillis() - startTime
                delay(100) // Update more frequently for smooth display
            }
        }
        // Note: We don't reset elapsedMs to 0 here - we want to keep showing the final time
    }
    
    // Handle navigation back after recording completes
    LaunchedEffect(recordingState, pendingNavigateBack) {
        if (pendingNavigateBack && recordingState is RecordingState.Idle) {
            // Recording has finished saving, now navigate back
            delay(200) // Brief delay for state to settle
            onNavigateBack()
        }
    }
    
    // Handle back gesture
    BackHandler(enabled = isActive) {
        showBackDialog = true
    }
    
    // Cancel confirmation dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Discard Recording?") },
            text = { Text("This recording will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        viewModel.cancelRecording()
                        onNavigateBack()
                    }
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Keep Recording")
                }
            }
        )
    }
    
    // Back gesture dialog (during recording)
    if (showBackDialog) {
        AlertDialog(
            onDismissRequest = { showBackDialog = false },
            title = { Text("Recording in Progress") },
            text = { Text("Would you like to save or discard this recording?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackDialog = false
                        pendingNavigateBack = true
                        viewModel.stopRecording()
                    }
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
                    }
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background glow layer
            RecordingGlowBackground(
                isRecording = isRecording,
                isPaused = false, // No pause state in current implementation
                modifier = Modifier.fillMaxSize()
            )
            
            // Main content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.weight(1f))
                
                // Timer display
                Text(
                    text = formatTimeMs(elapsedMs),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    ),
                    color = if (isRecording) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                
                // Status text
                Text(
                    text = when {
                        recordingState is RecordingState.Recording -> "Recording"
                        recordingState is RecordingState.Starting -> "Starting..."
                        recordingState is RecordingState.Stopping -> "Saving..."
                        hadRecordingSession -> "Saved"
                        else -> "Ready"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                // Audio waveform
                AudioWaveform(
                    amplitudes = amplitudeHistory,
                    isActive = isRecording,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                // Main action button
                // When recording: Shows STOP icon, stops and saves
                // When idle: Shows MIC icon, starts recording
                MainActionButton(
                    state = uiState,
                    recordingColor = MaterialTheme.colorScheme.error,
                    onStartRecording = { viewModel.startRecording() },
                    onStop = { 
                        // Main button during recording = STOP and SAVE
                        pendingNavigateBack = true
                        viewModel.stopRecording()
                    },
                    onResume = { viewModel.startRecording() }
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Secondary buttons (visible when recording)
                AnimatedVisibility(
                    visible = isActive,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
                    exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 }
                ) {
                    SecondaryButtonsRow(
                        onDone = { 
                            // Done = stop, save, and navigate back
                            pendingNavigateBack = true
                            viewModel.stopRecording()
                        },
                        onCancel = { 
                            // Cancel = show confirmation dialog
                            showCancelDialog = true 
                        },
                        onRestart = {
                            // Restart = discard current and start fresh (no navigation)
                            viewModel.cancelRecording()
                            // Small delay to let state reset before starting new recording
                            viewModel.startRecording()
                        }
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
