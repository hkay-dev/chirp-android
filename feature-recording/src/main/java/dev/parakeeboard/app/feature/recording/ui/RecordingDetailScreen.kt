package dev.parakeeboard.app.feature.recording.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.parakeeboard.app.core.ui.components.LoadingState
import dev.parakeeboard.app.core.util.formatAsDuration
import dev.parakeeboard.app.core.util.formatDateTime
import dev.parakeeboard.app.data.model.RecordingSource
import dev.parakeeboard.app.data.model.RecordingStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    onBackClick: () -> Unit,
    viewModel: RecordingDetailViewModel = hiltViewModel()
) {
    val recording by viewModel.recording.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val editedTitle by viewModel.editedTitle.collectAsState()
    
    // Load audio when recording becomes available
    LaunchedEffect(recording) {
        recording?.let { viewModel.loadAudio() }
    }
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    if (recording == null) {
        LoadingState()
        return
    }
    
    val rec = recording!!
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditing) {
                        TextField(
                            value = editedTitle,
                            onValueChange = { viewModel.updateTitle(it) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    } else {
                        Text(rec.title)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { viewModel.cancelEditing() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                        IconButton(onClick = { viewModel.saveTitle() }) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    } else {
                        IconButton(onClick = { viewModel.startEditing() }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit title")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Recording info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Duration
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = rec.durationMs.formatAsDuration(),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Metadata
                    DetailRow(label = "Created", value = rec.createdAt.formatDateTime())
                    DetailRow(
                        label = "Source",
                        value = when (rec.source) {
                            RecordingSource.APP -> "App"
                            RecordingSource.KEYBOARD -> "Keyboard"
                            RecordingSource.WIDGET -> "Widget"
                        }
                    )
                    DetailRow(
                        label = "Status",
                        value = when (rec.status) {
                            RecordingStatus.RECORDING -> "Recording"
                            RecordingStatus.PENDING_TRANSCRIPTION -> "Waiting for transcription"
                            RecordingStatus.TRANSCRIBING -> "Transcribing..."
                            RecordingStatus.PENDING_ENHANCEMENT -> "Waiting for processing"
                            RecordingStatus.ENHANCING -> "Processing..."
                            RecordingStatus.COMPLETED -> "Completed"
                            RecordingStatus.FAILED -> "Failed"
                        },
                        valueColor = when (rec.status) {
                            RecordingStatus.FAILED -> MaterialTheme.colorScheme.error
                            RecordingStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    
                    if (rec.status == RecordingStatus.FAILED && rec.errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = rec.errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            // Audio player
            AudioPlayerCard(
                playbackState = playbackState,
                onPlayPause = { viewModel.togglePlayPause() },
                onSeek = { viewModel.seekTo(it) },
                onSkipBackward = { viewModel.skipBackward() },
                onSkipForward = { viewModel.skipForward() },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            // Transcript section
            if (transcript != null) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Transcript",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Show processed text if available, otherwise raw
                        val text = transcript!!.processedText ?: transcript!!.rawText
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        // Summary if available
                        transcript!!.summary?.let { summary ->
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = "Summary",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (rec.status == RecordingStatus.PENDING_TRANSCRIPTION ||
                       rec.status == RecordingStatus.TRANSCRIBING) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (rec.status == RecordingStatus.TRANSCRIBING) {
                                "Transcribing..."
                            } else {
                                "Waiting for transcription..."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete recording?") },
            text = { Text("This action cannot be undone. The audio file and any transcription will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteRecording(onBackClick)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor
        )
    }
}
