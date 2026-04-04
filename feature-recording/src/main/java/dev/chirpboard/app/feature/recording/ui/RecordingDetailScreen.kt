package dev.chirpboard.app.feature.recording.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chirpboard.app.core.ui.components.LoadingState
import dev.chirpboard.app.core.util.formatForHeader
import dev.chirpboard.app.core.util.isDefaultDateTitle
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.recording.ui.components.MetadataPillRow
import dev.chirpboard.app.feature.recording.ui.components.StickyAudioPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    onBackClick: () -> Unit,
    viewModel: RecordingDetailViewModel = hiltViewModel(),
) {
    val recording by viewModel.recording.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val editedTitle by viewModel.editedTitle.collectAsState()
    val message by viewModel.message.collectAsState()
    val recoveryDiagnostics by viewModel.recoveryDiagnostics.collectAsState()
    val recoveryActions by viewModel.recoveryActions.collectAsState()

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

    if (recording == null) {
        LoadingState()
        return
    }

    val rec = recording!!
    // Determine display title - show "Voice Memo" if title is just a date
    val displayTitle = if (rec.title.isDefaultDateTitle()) "Voice Memo" else rec.title
    val dateTimeText = rec.createdAt.formatForHeader()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RecordingDetailTopBar(
                displayTitle = displayTitle,
                dateTimeText = dateTimeText,
                isEditing = isEditing,
                editedTitle = editedTitle,
                collapsedFraction = scrollBehavior.state.collapsedFraction,
                onEditedTitleChange = { viewModel.updateTitle(it) },
                onCancelEditing = { viewModel.cancelEditing() },
                onSaveTitle = { viewModel.saveTitle() },
                onStartEditing = { viewModel.startEditing() },
                onDeleteRequested = { showDeleteDialog = true },
                onBackClick = onBackClick,
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = true,
                enter =
                    slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ) + fadeIn(tween(300)),
                exit =
                    slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(200),
                    ) + fadeOut(tween(200)),
            ) {
                StickyAudioPlayer(
                    playbackState = playbackState,
                    onPlayPause = { viewModel.togglePlayPause() },
                    onSeek = { viewModel.seekTo(it) },
                    onSkipBackward = { viewModel.skipBackward() },
                    onSkipForward = { viewModel.skipForward() },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState()),
        ) {
            // Metadata pill row
            Spacer(modifier = Modifier.height(8.dp))
            MetadataPillRow(
                durationMs = rec.durationMs,
                source = rec.source,
                status = rec.status,
            )

            Spacer(modifier = Modifier.height(16.dp))

            RecordingDetailSummarySection(summary = transcript?.summary)

            RecordingDetailTranscriptSection(
                recording = rec,
                transcript = transcript,
                recoveryDiagnostics = recoveryDiagnostics,
                recoveryActions = recoveryActions,
                onShareAudio = { viewModel.shareAudio() },
                onShareTranscript = { viewModel.shareTranscript() },
                onShareBoth = { viewModel.shareBoth() },
                onRetryTranscription = { viewModel.retryTranscription() },
                onRecoverEnhancing = { viewModel.recoverEnhancing() },
                onRetranscribe = { viewModel.retranscribeFromEnhancing() },
                onRecoverPending = { viewModel.recoverPendingTranscription() },
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    RecordingDetailDeleteDialog(
        visible = showDeleteDialog,
        onDismissRequest = { showDeleteDialog = false },
        onDeleteConfirmed = {
            showDeleteDialog = false
            viewModel.deleteRecording(onBackClick)
        },
    )
}
