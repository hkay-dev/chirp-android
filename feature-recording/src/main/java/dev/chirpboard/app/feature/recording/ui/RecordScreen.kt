package dev.chirpboard.app.feature.recording.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.RepositoryErrorSnackbarEffect
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.session.RecoverableRecordingSession
import dev.chirpboard.app.core.ui.components.recording.AudioWaveform
import dev.chirpboard.app.core.ui.components.recording.RecordingActionRow
import dev.chirpboard.app.core.ui.components.recording.RecordingGlowBackground
import dev.chirpboard.app.core.ui.components.recording.RecordingTimer
import dev.chirpboard.app.core.ui.motion.ChirpMotion
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
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val isProfileHandoffResolved by viewModel.isProfileHandoffResolved.collectAsStateWithLifecycle()
    val entryMessage by viewModel.entryMessage.collectAsStateWithLifecycle()
    val recoverableSessions by viewModel.recoverableSessions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCancelDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showBackDialog by remember { mutableStateOf(false) }
    var recoveryPromptSession by remember { mutableStateOf<RecoverableRecordingSession?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val isRecording =
        recordingState is RecordingState.Recording ||
            recordingState is RecordingState.Starting ||
            recordingState is RecordingState.Stopping
    val isPaused = recordingState is RecordingState.Paused
    val isActive = isRecording || isPaused

    LaunchedEffect(recoverableSessions) {
        if (recoveryPromptSession == null && recoverableSessions.isNotEmpty()) {
            recoveryPromptSession = recoverableSessions.first()
        }
    }

    LaunchedEffect(autoStart, isProfileHandoffResolved, recoverableSessions) {
        if (
            autoStart &&
            isProfileHandoffResolved &&
            recoverableSessions.isEmpty() &&
            recordingState is RecordingState.Idle
        ) {
            viewModel.startRecording(context)
        }
    }

    LaunchedEffect(lastCompletedRecordingId) {
        val recordingId = lastCompletedRecordingId
        if (recordingId != null) {
            delay(ChirpMotion.RECORD_HANDOFF_MS)
            onRecordingComplete(recordingId.toString())
            viewModel.clearLastCompletedRecordingId()
        }
    }

    RepositoryErrorSnackbarEffect(
        errorMessage = entryMessage,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearEntryMessage,
    )

    BackHandler(enabled = isActive) {
        showBackDialog = true
    }

    recoveryPromptSession?.let { session ->
        AlertDialog(
            onDismissRequest = { recoveryPromptSession = null },
            title = { Text(stringResource(R.string.rec_recovery_title)) },
            text = {
                Text(
                    if (session.hasPotentialLoss) {
                        stringResource(
                            R.string.rec_recovery_message_with_loss,
                            session.estimatedLostMinutes(),
                        )
                    } else {
                        stringResource(R.string.rec_recovery_message)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.recoverInterruptedSession(session.sessionId)
                        recoveryPromptSession = null
                    },
                ) {
                    Text(stringResource(R.string.rec_recovery_recover))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.keepInterruptedSession(session.sessionId)
                            recoveryPromptSession = null
                        },
                    ) {
                        Text(stringResource(R.string.rec_recovery_keep))
                    }
                    TextButton(
                        onClick = {
                            viewModel.discardInterruptedSession(session.sessionId)
                            recoveryPromptSession = null
                        },
                    ) {
                        Text(stringResource(R.string.rec_recovery_discard))
                    }
                }
            },
        )
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
                title = { Text(stringResource(R.string.new_recording_title)) },
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            activeProfile?.let { profile ->
                ActiveProfileSessionBadge(
                    profile = profile,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            RecordingTimer(
                recordingState = recordingState,
                isRecording = isRecording,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isRecording) {
                        RecordingGlowBackground(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.extraLarge)
                        )
                    }
                    
                    RecordingWaveform(
                        viewModel = viewModel,
                        isRecording = isRecording,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            RecordingActionRow(
                isRecording = isRecording,
                isPaused = isPaused,
                onTogglePausePlay = {
                    if (isPaused || !isActive) {
                        if (isActive) {
                            viewModel.resumeRecording(context)
                        } else if (isProfileHandoffResolved) {
                            viewModel.startRecording(context)
                        }
                    } else {
                        viewModel.pauseRecording(context)
                    }
                },
                onStopRecording = { viewModel.stopRecording(context) },
                onRestartRecording = { showRestartDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
private fun ActiveProfileSessionBadge(
    profile: ActiveRecordingProfile,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.rec_active_profile_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = profile.icon
                if (!icon.isNullOrBlank()) {
                    Text(
                        text = icon,
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
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