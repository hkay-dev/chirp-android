package dev.chirpboard.app.feature.recording.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.util.formatForHeader
import dev.chirpboard.app.core.util.isDefaultDateTitle
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.LoadingState
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.recording.ui.components.ContentSection
import dev.chirpboard.app.feature.recording.ui.components.MetadataPillRow
import dev.chirpboard.app.feature.recording.ui.components.StickyAudioPlayer

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
    val message by viewModel.message.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    
    // Show messages
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }
    
    // Load audio when recording becomes available and is ready for playback
    LaunchedEffect(recording?.id, recording?.status) {
        recording?.let { rec ->
            if (rec.status != RecordingStatus.RECORDING) {
                kotlinx.coroutines.delay(200)
                viewModel.loadAudio()
            }
        }
    }
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    
    if (recording == null) {
        LoadingState()
        return
    }
    
    val rec = recording!!
    val hasTranscript = transcript != null
    val hasSummary = transcript?.summary != null
    
    // Determine display title - show "Voice Memo" if title is just a date
    val displayTitle = if (rec.title.isDefaultDateTitle()) "Voice Memo" else rec.title
    val dateTimeText = rec.createdAt.formatForHeader()
    
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    if (isEditing) {
                        TextField(
                            value = editedTitle,
                            onValueChange = { viewModel.updateTitle(it) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    } else {
                        Column {
                            Text(
                                text = displayTitle,
                                maxLines = if (scrollBehavior.state.collapsedFraction > 0.5f) 1 else 3,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold
                            )
                            // Show date/time subtitle only when expanded
                            if (scrollBehavior.state.collapsedFraction < 0.5f) {
                                Text(
                                    text = dateTimeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
                        // Overflow menu with edit and delete
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Edit title") },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.startEditing()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Edit, contentDescription = null)
                                    }
                                )
                                
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showDeleteDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            StickyAudioPlayer(
                playbackState = playbackState,
                onPlayPause = { viewModel.togglePlayPause() },
                onSeek = { viewModel.seekTo(it) },
                onSkipBackward = { viewModel.skipBackward() },
                onSkipForward = { viewModel.skipForward() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Metadata pill row
            Spacer(modifier = Modifier.height(8.dp))
            MetadataPillRow(
                durationMs = rec.durationMs,
                source = rec.source,
                status = rec.status
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Summary section (first, if available)
            AnimatedVisibility(
                visible = hasSummary,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    // Summary with subtle background
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Summary",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = transcript?.summary ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            
            // Transcript section
            AnimatedContent(
                targetState = Triple(hasTranscript, rec.status, transcript?.rawText),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "transcript-content"
            ) { (hasText, status, _) ->
                when {
                    hasText -> {
                        ContentSection(
                            title = "Transcript",
                            action = if (status == RecordingStatus.COMPLETED) {
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Share button with dropdown
                                        Box {
                                            IconButton(
                                                onClick = { showShareMenu = true },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = "Share",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            
                                            DropdownMenu(
                                                expanded = showShareMenu,
                                                onDismissRequest = { showShareMenu = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Share audio") },
                                                    onClick = {
                                                        showShareMenu = false
                                                        viewModel.shareAudio()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.AudioFile, contentDescription = null)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Share transcript") },
                                                    onClick = {
                                                        showShareMenu = false
                                                        viewModel.shareTranscript()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Description, contentDescription = null)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Share both") },
                                                    onClick = {
                                                        showShareMenu = false
                                                        viewModel.shareBoth()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.FolderZip, contentDescription = null)
                                                    }
                                                )
                                            }
                                        }
                                        
                                        // Re-run button
                                        TextButton(
                                            onClick = { viewModel.retryTranscription() },
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Re-run", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            } else null
                        ) {
                            val text = transcript?.processedText ?: transcript?.rawText ?: ""
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    
                    status == RecordingStatus.PENDING_TRANSCRIPTION ||
                    status == RecordingStatus.TRANSCRIBING -> {
                        // Processing state
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (status == RecordingStatus.TRANSCRIBING) {
                                        "Transcribing..."
                                    } else {
                                        "Waiting for transcription..."
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            
                            if (status == RecordingStatus.TRANSCRIBING) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            }
                        }
                    }
                    
                    status == RecordingStatus.FAILED -> {
                        // Error state with retry
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = "Transcription failed",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            
                            if (rec.errorMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = rec.errorMessage!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            FilledTonalButton(
                                onClick = { viewModel.retryTranscription() },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry Transcription")
                            }
                        }
                    }
                    
                    else -> {
                        // Pending or other state - show minimal placeholder
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No transcript available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AnimatedAlertDialog(
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
