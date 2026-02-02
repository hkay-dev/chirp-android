package dev.parakeeboard.app.feature.recording.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.parakeeboard.app.core.recording.RecordingState
import dev.parakeeboard.app.core.ui.components.EmptyState
import dev.parakeeboard.app.core.util.formatAsDuration
import dev.parakeeboard.app.data.entity.Recording

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRecordingClick: (Recording) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val recordings by viewModel.recordings.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Show error messages
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }
    
    // Also show errors from recording state
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Error) {
            val error = recordingState as RecordingState.Error
            snackbarHostState.showSnackbar(
                message = error.message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }
    
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Parakeeboard") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            RecordButton(
                recordingState = recordingState,
                onClick = { viewModel.toggleRecording() }
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (recordings.isEmpty() && recordingState is RecordingState.Idle) {
            EmptyState(
                icon = Icons.Default.MicNone,
                title = "No recordings yet",
                subtitle = "Tap the microphone button to start recording",
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 120.dp // Extra padding for FAB
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Recording in progress card
                if (recordingState is RecordingState.Recording) {
                    item(key = "current_recording") {
                        CurrentRecordingCard(
                            recordingState = recordingState as RecordingState.Recording,
                            onStopClick = { viewModel.toggleRecording() }
                        )
                    }
                }
                
                items(
                    items = recordings,
                    key = { it.id }
                ) { recording ->
                    RecordingCard(
                        recording = recording,
                        onClick = { onRecordingClick(recording) },
                        onDelete = { viewModel.deleteRecording(recording) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentRecordingCard(
    recordingState: RecordingState.Recording,
    onStopClick: () -> Unit
) {
    val duration = System.currentTimeMillis() - recordingState.startTimeMs
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Recording in progress",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = duration.formatAsDuration(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            
            FilledTonalButton(
                onClick = onStopClick,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Stop")
            }
        }
    }
}
