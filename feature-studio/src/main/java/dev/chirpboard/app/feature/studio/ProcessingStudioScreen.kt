package dev.chirpboard.app.feature.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.core.ui.components.MetadataPillRow
import dev.chirpboard.app.core.ui.playback.RecordingFullPlayer
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.studio.R
import dev.chirpboard.app.feature.studio.tabs.ChatTab
import dev.chirpboard.app.feature.studio.tabs.SummaryTab
import dev.chirpboard.app.feature.studio.tabs.StudioProcessingHeader
import dev.chirpboard.app.feature.studio.tabs.TranscriptTab
import dev.chirpboard.app.feature.studio.tabs.transcriptionProgressCopy
import dev.chirpboard.app.feature.studio.tabs.transcriptionProgressKind
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProcessingStudioScreen(
    recordingId: String,
    onNavigateBack: () -> Unit,
    viewModel: ProcessingStudioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val screenRecordingId = remember(recordingId) { runCatching { UUID.fromString(recordingId) }.getOrNull() }

    when (state.loadState) {
        ProcessingStudioLoadState.InvalidId -> {
            ProcessingStudioBarrierScreen(
                title = stringResource(R.string.rec_studio_invalid_recording_title),
                description = stringResource(R.string.rec_studio_invalid_recording_message),
                onNavigateBack = onNavigateBack,
            )
            return
        }

        ProcessingStudioLoadState.NotFound -> {
            ProcessingStudioBarrierScreen(
                title = stringResource(R.string.rec_studio_recording_not_found_title),
                description = stringResource(R.string.rec_studio_recording_not_found_message),
                onNavigateBack = onNavigateBack,
            )
            return
        }

        ProcessingStudioLoadState.Loading -> Unit
        ProcessingStudioLoadState.Ready -> Unit
    }

    val failurePresentation =
        remember(state.status, state.errorMessage, state.recoveryActions) {
            studioFailurePresentation(
                status = state.status,
                errorMessage = state.errorMessage,
                recoveryActions = state.recoveryActions,
            )
        }

    val showMetadataSkeleton =
        state.loadState == ProcessingStudioLoadState.Loading ||
            state.isLoading ||
            state.title.isBlank()

    val tabs = listOf(stringResource(R.string.rec_transcript), stringResource(R.string.rec_summary), stringResource(R.string.rec_chat))
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    var showRetranscribeConfirmation by remember { mutableStateOf(false) }

    val canEditTranscript =
        state.effectiveTranscriptText.isNotBlank() &&
            !state.isEditingTranscript &&
            state.status.transcriptionProgressKind() == null
    val canRetranscribe =
        state.effectiveTranscriptText.isNotBlank() &&
            !state.isEditingTranscript &&
            state.status.transcriptionProgressKind() == null

    LaunchedEffect(message) {
        val currentMessage = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(currentMessage)
        viewModel.clearMessage()
    }

    if (showRetranscribeConfirmation) {
        AnimatedAlertDialog(
            onDismissRequest = { showRetranscribeConfirmation = false },
            title = { Text(stringResource(R.string.rec_retranscribe)) },
            text = {
                Text(stringResource(R.string.rec_transcript_retranscribe_confirmation))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showRetranscribeConfirmation = false
                        viewModel.retranscribe()
                    },
                ) {
                    Text(stringResource(R.string.rec_retranscribe))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRetranscribeConfirmation = false }) {
                    Text(stringResource(CoreR.string.rec_cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.rec_details)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreR.string.desc_navigate_back))
                        }
                    },
                    actions = {
                        if (state.isEditingTranscript) {
                            IconButton(onClick = viewModel::cancelEditingTranscript) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(CoreR.string.desc_cancel))
                            }
                            IconButton(onClick = viewModel::saveTranscriptCorrection) {
                                Icon(Icons.Default.Check, contentDescription = stringResource(CoreR.string.desc_save))
                            }
                        } else {
                        Box {
                            IconButton(onClick = { showShareMenu = true }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(CoreR.string.desc_share))
                            }
                            DropdownMenu(expanded = showShareMenu, onDismissRequest = { showShareMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreR.string.rec_share_audio)) },
                                    onClick = {
                                        showShareMenu = false
                                        viewModel.shareAudio(context)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreR.string.rec_share_transcript)) },
                                    onClick = {
                                        showShareMenu = false
                                        viewModel.shareTranscript(context)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreR.string.rec_share_both)) },
                                    onClick = {
                                        showShareMenu = false
                                        viewModel.shareBoth(context)
                                    },
                                )
                            }
                        }
                            IconButton(
                                onClick = {
                                    if (state.hasManualCorrection) {
                                        showRetranscribeConfirmation = true
                                    } else {
                                        viewModel.retranscribe()
                                    }
                                },
                                enabled = canRetranscribe,
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.rec_retranscribe_desc),
                                )
                            }
                            IconButton(
                                onClick = viewModel::startEditingTranscript,
                                enabled = canEditTranscript,
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.rec_edit_transcript),
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showOptionsMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(CoreR.string.desc_more_options))
                            }
                            DropdownMenu(expanded = showOptionsMenu, onDismissRequest = { showOptionsMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rec_edit_title)) },
                                    onClick = {
                                        showOptionsMenu = false
                                        viewModel.startEditingTitle()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = stringResource(CoreR.string.desc_edit)) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreR.string.rec_delete)) },
                                    onClick = {
                                        showOptionsMenu = false
                                        viewModel.deleteRecording { onNavigateBack() }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(CoreR.string.desc_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                )
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = { Text(title) },
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding()
                    .animateContentSize(
                        animationSpec = dev.chirpboard.app.core.ui.motion.ChirpMotion.layoutSizeSpring,
                    ),
        ) {
            // Metadata Bar
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animatePushDownLayout()) {
                AnimatedContent(
                    targetState = Triple(state.isEditingTitle, showMetadataSkeleton, state.title),
                    transitionSpec = { ChirpMotion.studioContentCrossfade },
                    label = "studio_title_metadata",
                ) { (editing, skeleton, title) ->
                    when {
                        editing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextField(
                                    value = state.editedTitle,
                                    onValueChange = viewModel::updateEditedTitle,
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors =
                                        TextFieldDefaults.colors(
                                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                        ),
                                )
                                IconButton(onClick = viewModel::cancelEditingTitle) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(CoreR.string.desc_cancel))
                                }
                                IconButton(onClick = viewModel::saveTitle) {
                                    Icon(Icons.Default.Check, contentDescription = stringResource(CoreR.string.desc_save))
                                }
                            }
                        }

                        skeleton -> {
                            Text(
                                text = stringResource(R.string.rec_studio_loading_title),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (showMetadataSkeleton) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(3) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(width = 88.dp, height = 28.dp),
                            ) {}
                        }
                    }
                } else {
                    MetadataPillRow(
                        createdAtMs = state.createdAt,
                        durationMs = state.durationMs,
                        source = state.source ?: RecordingSource.APP,
                    )
                }
            }

            val progressCopy = state.status.transcriptionProgressCopy()
            val progressKind = state.status.transcriptionProgressKind()
            val showPlayer =
                screenRecordingId != null &&
                    state.playerRevealReady &&
                    state.audioPath.isNotBlank() &&
                    state.status != RecordingStatus.RECORDING

            StudioProcessingHeader(
                modifier = Modifier.fillMaxWidth(),
                progressCopy = progressCopy,
                progressKind = progressKind,
                showPlayer = showPlayer,
                playerContent = {
                    val alternateNotice =
                        playbackState.recordingId?.takeIf { it != screenRecordingId && playbackState.isPlaying }?.let { _ ->
                            stringResource(
                                CoreR.string.playback_other_recording_notice,
                                playbackState.title,
                            )
                        }
                    RecordingFullPlayer(
                        state = playbackState,
                        screenRecordingId = screenRecordingId!!,
                        screenTitle = state.title,
                        alternateAudioNotice = alternateNotice,
                        onPlayPause = viewModel::togglePlayPause,
                        onSeek = viewModel::seekTo,
                        onSkipBackward = viewModel::skipBackward,
                        onSkipForward = viewModel::skipForward,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
            )

            PushDownReveal(visible = failurePresentation.showRecoverySection) {
                TranscriptionRecoverySection(
                    recoveryActions = state.recoveryActions,
                    diagnostics = state.recoveryDiagnostics,
                    onRecoverPending = viewModel::recoverPendingTranscription,
                    onRecoverEnhancing = viewModel::recoverEnhancing,
                    onRetranscribeFromEnhancing = viewModel::retranscribeFromEnhancing,
                    onRetryFailed = viewModel::retryTranscription,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            PushDownReveal(visible = failurePresentation.showErrorBanner) {
                val isFailure = state.status == RecordingStatus.FAILED
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isFailure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isFailure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = stringResource(R.string.rec_processing_error),
                            )
                            Text(
                                text = state.errorMessage.asProcessingMessage() ?: stringResource(CoreR.string.rec_status_failed),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (failurePresentation.showRetryOnErrorBanner) {
                            FilledTonalButton(
                                onClick = viewModel::retryTranscription,
                                enabled = state.recoveryActions.actionsEnabled,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.rec_retry_transcription_cap))
                            }
                        }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> {
                        TranscriptTab(
                            transcript = state.transcript,
                            effectiveTranscriptText = state.effectiveTranscriptText,
                            rawTranscriptText = state.rawTranscriptText,
                            enhancedTranscriptText = state.enhancedTranscriptText,
                            llmProcessingEnabled = state.llmProcessingEnabled,
                            transcriptDraft = state.transcriptDraft,
                            isEditingTranscript = state.isEditingTranscript,
                            hasManualCorrection = state.hasManualCorrection,
                            activeSegmentIndex = state.activeTranscriptSegmentIndex,
                            status = state.status,
                            onSegmentClicked = if (state.canUseTranscriptInteractions()) viewModel::onWordClicked else null,
                            onTranscriptDraftChange = viewModel::updateTranscriptDraft,
                            onCopyTranscript = {
                                val text = state.effectiveTranscriptText.trim()
                                if (text.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(text))
                                    viewModel.onTranscriptCopied()
                                }
                            },
                            onCopyOriginal = {
                                val text = state.rawTranscriptText.trim()
                                if (text.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(text))
                                    viewModel.onTranscriptCopied()
                                }
                            },
                            onCopyEnhanced = {
                                val text = state.enhancedTranscriptText.trim()
                                if (text.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(text))
                                    viewModel.onTranscriptCopied()
                                }
                            },
                        )
                    }

                    1 -> {
                        SummaryTab(
                            summaryMarkdown = state.summary,
                            structuredOutcomeSection = state.structuredOutcomeSection,
                            onGenerateStructuredOutcomes = viewModel::generateStructuredOutcomes,
                            onCopyStructuredOutcome = { item ->
                                clipboardManager.setText(AnnotatedString(item.text))
                                viewModel.onStructuredOutcomeCopied()
                            },
                            onShareStructuredOutcome = { item -> viewModel.shareStructuredOutcome(context, item) },
                            onAskAiAboutStructuredOutcome = { item ->
                                viewModel.draftStructuredOutcomeQuestion(item)
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(2)
                                }
                            },
                        )
                    }

                    2 -> {
                        ChatTab(
                            messages = state.chatMessages,
                            draftMessage = state.chatDraft,
                            onDraftMessageChange = viewModel::updateChatDraft,
                            onSendMessage = viewModel::onSendChatMessage,
                            isTyping = state.isTyping,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessingStudioBarrierScreen(
    title: String,
    description: String,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rec_details)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreR.string.desc_navigate_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        EmptyState(
            icon = Icons.Default.Error,
            title = title,
            description = description,
            modifier = Modifier.padding(paddingValues),
            actionLabel = stringResource(R.string.rec_studio_go_back),
            onAction = onNavigateBack,
        )
    }
}

private fun String?.asProcessingMessage(): String? =
    this
        ?.removePrefix("recoverable_queue_handoff:")
        ?.removePrefix("recoverable_stale_transcribing:")
        ?.removePrefix("recoverable_stale_enhancing:")
        ?.removePrefix("manual_recovery:")
        ?.substringBefore("|attemptAt=")
        ?.ifBlank { null }
