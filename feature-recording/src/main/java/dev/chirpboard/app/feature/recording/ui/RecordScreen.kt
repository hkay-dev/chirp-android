package dev.chirpboard.app.feature.recording.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import dev.chirpboard.app.core.ui.components.recording.AudioWaveform
import dev.chirpboard.app.core.ui.components.recording.RecordingActionRow
import dev.chirpboard.app.core.ui.components.recording.RecordingGlowBackground
import dev.chirpboard.app.core.ui.components.recording.RecordingTimer
import dev.chirpboard.app.core.util.formatTimeMs
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
    val lastCompletedRecordingId by viewModel.lastCompletedRecordingId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCancelDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showBackDialog by remember { mutableStateOf(false) }

    val isRecording =
        recordingState is RecordingState.Recording ||
            recordingState is RecordingState.Starting ||
            recordingState is RecordingState.Stopping
    val isPaused = recordingState is RecordingState.Paused
    val isActive = isRecording || isPaused

    LaunchedEffect(autoStart) {
        if (autoStart && recordingState is RecordingState.Idle) {
            viewModel.startRecording(context)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Recording") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isActive) {
                                showCancelDialog = true
                            } else {
                                onNavigateBack()
                            }
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.desc_close))
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (isRecording) {
                RecordingGlowBackground(modifier = Modifier.fillMaxSize())
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                RecordingTimer(
                    recordingState = recordingState,
                    isRecording = isRecording,
                )

                Spacer(modifier = Modifier.height(48.dp))

                RecordingWaveform(
                    viewModel = viewModel,
                    isRecording = isRecording,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                )

                Spacer(modifier = Modifier.height(32.dp))

                RecordingActionRow(
                    isRecording = isRecording,
                    isPaused = isPaused,
                    onTogglePausePlay = {
                        if (isPaused || !isActive) {
                            if (isActive) {
                                viewModel.resumeRecording(context)
                            } else {
                                viewModel.startRecording(context)
                            }
                        } else {
                            viewModel.pauseRecording(context)
                        }
                    },
                    onStopRecording = { viewModel.stopRecording(context) },
                    onRestartRecording = { showRestartDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
                )
            }
        }
    }
}


@Composable
private fun RecordingWaveform(
    viewModel: RecordViewModel,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
 ) {
    val amplitudeSampleCount by viewModel.amplitudeSampleCount.collectAsStateWithLifecycle()

    AudioWaveform(
        waveformBuffer = viewModel.waveformBuffer,
        sampleCount = amplitudeSampleCount,
        isActive = isRecording,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}