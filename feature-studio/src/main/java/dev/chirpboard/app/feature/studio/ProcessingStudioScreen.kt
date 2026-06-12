package dev.chirpboard.app.feature.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.contracts.R as ContractsR
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.ChirpPill
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.core.ui.components.MetadataPillRow
import dev.chirpboard.app.core.ui.components.SkeletonPlaceholder
import dev.chirpboard.app.core.ui.components.icon
import dev.chirpboard.app.core.ui.components.label
import dev.chirpboard.app.core.util.formatRelative
import dev.chirpboard.app.core.playback.RecordingPlaybackState
import dev.chirpboard.app.core.ui.haptics.ChirpHaptics
import dev.chirpboard.app.core.ui.playback.RecordingFullPlayer
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.studio.R
import dev.chirpboard.app.feature.studio.tabs.ChatTab
import dev.chirpboard.app.feature.studio.tabs.SummaryTab
import dev.chirpboard.app.feature.studio.tabs.StudioProcessingHeader
import dev.chirpboard.app.feature.studio.tabs.TranscriptTab
import dev.chirpboard.app.core.ui.components.transcriptionProgressCopy
import dev.chirpboard.app.core.ui.components.transcriptionProgressKind
import kotlinx.coroutines.launch
import java.util.Date
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
    val promotionPrompt by viewModel.promotionPrompt.collectAsStateWithLifecycle()
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
    val transcriptClipLabel = stringResource(R.string.rec_transcript)
    val snackbarHostState = remember { SnackbarHostState() }
    // LIF-12: open menus + pending confirmations survive rotation/resize/process death.
    var showOptionsMenu by rememberSaveable { mutableStateOf(false) }
    var showShareMenu by rememberSaveable { mutableStateOf(false) }
    var showRetranscribeConfirmation by rememberSaveable { mutableStateOf(false) }
    // Destructive recording delete always requires an explicit confirmation (same policy as
    // Home); undo is intentionally not offered because the row, its cascade-deleted transcript,
    // and the audio file are unrecoverable once removed.
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    // LIF-11: back gesture in edit mode confirms before discarding an in-progress correction.
    var showDiscardEditDialog by rememberSaveable { mutableStateOf(false) }
    // NOTES: collapsed/expanded display state for the note card survives rotation.
    var isNotesExpanded by rememberSaveable { mutableStateOf(false) }

    fun requestCloseTranscriptEdit() {
        if (state.transcriptDraft != state.effectiveTranscriptText) {
            showDiscardEditDialog = true
        } else {
            viewModel.cancelEditingTranscript()
        }
    }

    val canEditTranscript =
        state.effectiveTranscriptText.isNotBlank() &&
            !state.isEditingTranscript &&
            state.status.transcriptionProgressKind() == null
    // AWAITING_MANUAL_TRANSCRIPTION rows (profile Auto Transcribe off or a user cancel)
    // have no transcript yet but must keep a working way to start transcription (PLH-4).
    val canRetranscribe =
        (
            state.effectiveTranscriptText.isNotBlank() ||
                state.status == RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION
        ) &&
            !state.isEditingTranscript &&
            state.status.transcriptionProgressKind() == null

    LaunchedEffect(message) {
        val currentMessage = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(currentMessage)
        viewModel.clearMessage()
    }

    // PLH-7: after a saved correction reduces to one word/phrase substitution, offer to promote
    // it to a global Word Replacement via an actionable snackbar.
    val promotionActionLabel = stringResource(R.string.rec_promotion_action)
    LaunchedEffect(promotionPrompt) {
        val prompt = promotionPrompt ?: return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message =
                    context.getString(
                        R.string.rec_promotion_prompt,
                        prompt.original,
                        prompt.replacement,
                    ),
                actionLabel = promotionActionLabel,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.promoteTranscriptCorrection()
        }
        viewModel.clearPromotionPrompt()
    }

    // LIF-11: intercept predictive back while a modal edit/selection is active instead of
    // silently popping the screen (and the draft) away.
    BackHandler(
        enabled =
            state.isEditingTranscript ||
                state.isEditingTitle ||
                state.isSelectingTranscript ||
                state.isEditingNotes,
    ) {
        when {
            state.isSelectingTranscript -> viewModel.exitTranscriptSelectionMode()
            state.isEditingTranscript -> requestCloseTranscriptEdit()
            state.isEditingNotes -> viewModel.cancelEditingNotes()
            else -> viewModel.cancelEditingTitle()
        }
    }

    if (showDiscardEditDialog) {
        AnimatedAlertDialog(
            onDismissRequest = { showDiscardEditDialog = false },
            title = { Text(stringResource(R.string.rec_discard_edit_title)) },
            text = { Text(stringResource(R.string.rec_discard_edit_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardEditDialog = false
                        viewModel.cancelEditingTranscript()
                    },
                ) {
                    Text(stringResource(R.string.rec_discard_edit_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardEditDialog = false }) {
                    Text(stringResource(R.string.rec_discard_edit_keep))
                }
            },
        )
    }

    if (showDeleteConfirmation) {
        AnimatedAlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(CoreR.string.rec_delete_recording_title)) },
            text = { Text(stringResource(CoreR.string.rec_delete_recording_message, state.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        // PRM-1: heavy thunk on the destructive confirm, matching Home.
                        ChirpHaptics.delete(context)
                        viewModel.deleteRecording { onNavigateBack() }
                    },
                ) {
                    Text(stringResource(CoreR.string.rec_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(CoreR.string.rec_cancel))
                }
            },
        )
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
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(CoreR.string.desc_navigate_back))
                        }
                    },
                    actions = {
                        if (state.isEditingTranscript) {
                            IconButton(onClick = { requestCloseTranscriptEdit() }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(CoreR.string.desc_cancel))
                            }
                            IconButton(onClick = viewModel::saveTranscriptCorrection) {
                                Icon(Icons.Rounded.Check, contentDescription = stringResource(CoreR.string.desc_save))
                            }
                        } else if (state.isSelectingTranscript) {
                            // PLH-6: selection mode replaces the regular actions with Done.
                            TextButton(onClick = viewModel::exitTranscriptSelectionMode) {
                                Text(stringResource(R.string.rec_transcript_selection_done))
                            }
                        } else {
                        Box {
                            IconButton(onClick = { showShareMenu = true }) {
                                Icon(Icons.Rounded.Share, contentDescription = stringResource(CoreR.string.desc_share))
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
                                    Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.rec_retranscribe_desc),
                                )
                            }
                            IconButton(
                                onClick = viewModel::startEditingTranscript,
                                enabled = canEditTranscript,
                            ) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = stringResource(R.string.rec_edit_transcript),
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showOptionsMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(CoreR.string.desc_more_options))
                            }
                            DropdownMenu(expanded = showOptionsMenu, onDismissRequest = { showOptionsMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rec_edit_title)) },
                                    onClick = {
                                        showOptionsMenu = false
                                        viewModel.startEditingTitle()
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = stringResource(CoreR.string.desc_edit)) },
                                )
                                // NOTES: the section is hidden while the recording has no note,
                                // so this is the discoverable way to add one from the studio.
                                if (state.notes.isBlank() && !state.isEditingNotes) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rec_add_note)) },
                                        onClick = {
                                            showOptionsMenu = false
                                            viewModel.startEditingNotes()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.StickyNote2,
                                                contentDescription = null,
                                            )
                                        },
                                    )
                                }
                                // PLH-6: entry point for transcript passage selection + AI tools.
                                if (state.canEnterTranscriptSelectionMode()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rec_select_text)) },
                                        onClick = {
                                            showOptionsMenu = false
                                            viewModel.enterTranscriptSelectionMode()
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(0)
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.SelectAll,
                                                contentDescription = null,
                                            )
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreR.string.rec_delete)) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showDeleteConfirmation = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Delete,
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
                                val titleFieldDescription = stringResource(R.string.rec_title_field_desc)
                                TextField(
                                    value = state.editedTitle,
                                    onValueChange = viewModel::updateEditedTitle,
                                    singleLine = true,
                                    // A11Y: name the edit box so TalkBack says what is being edited.
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .semantics { contentDescription = titleFieldDescription },
                                    colors =
                                        TextFieldDefaults.colors(
                                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                        ),
                                )
                                IconButton(onClick = viewModel::cancelEditingTitle) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(CoreR.string.desc_cancel))
                                }
                                IconButton(onClick = viewModel::saveTitle) {
                                    Icon(Icons.Rounded.Check, contentDescription = stringResource(CoreR.string.desc_save))
                                }
                            }
                        }

                        skeleton -> {
                            // LOAD-8: a shimmering title-shaped placeholder reads more premium than
                            // the literal word "Loading…". Size it like a headlineSmall title line.
                            // A11Y: announce the load instead of reading as a blank header.
                            val loadingDescription = stringResource(R.string.rec_studio_loading_title)
                            Box(modifier = Modifier.semantics { contentDescription = loadingDescription }) {
                                SkeletonPlaceholder(
                                    width = 200.dp,
                                    height = 28.dp,
                                )
                            }
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
                        // LOAD-8: shimmering pill placeholders, cohesive with the title skeleton.
                        repeat(3) {
                            SkeletonPlaceholder(
                                width = 88.dp,
                                height = 28.dp,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                        }
                    }
                } else {
                    StudioMetadataPills(
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
                    // Until Media3 has loaded THIS recording (prepare is deferred, and skipped
                    // while another recording owns the controller), the raw playback state has
                    // no duration, so the player read "0:00" with a dead scrubber. Seed the
                    // persisted row duration so the total time and seek math are right from the
                    // first frame; live playback state stays authoritative once it reports.
                    val playerState =
                        when {
                            playbackState.recordingId != screenRecordingId ->
                                RecordingPlaybackState(
                                    recordingId = screenRecordingId,
                                    title = state.title,
                                    audioPath = state.audioPath,
                                    durationMs = state.durationMs,
                                    playbackSpeed = playbackState.playbackSpeed,
                                )
                            playbackState.durationMs <= 0 && state.durationMs > 0 ->
                                playbackState.copy(durationMs = state.durationMs)
                            else -> playbackState
                        }
                    RecordingFullPlayer(
                        state = playerState,
                        screenRecordingId = screenRecordingId!!,
                        screenTitle = state.title,
                        alternateAudioNotice = alternateNotice,
                        onPlayPause = {
                            // PRM-1: confirming tick on play/pause, matching the keyboard's tactile language.
                            ChirpHaptics.tap(context)
                            viewModel.togglePlayPause()
                        },
                        onSeek = viewModel::seekTo,
                        onSkipBackward = viewModel::skipBackward,
                        onSkipForward = viewModel::skipForward,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
            )

            // NOTES: the capture-time description, hidden entirely unless the recording carries
            // a note (or one is being written via the options menu). It sits between the player
            // and the tabs so it frames whichever tab is open.
            PushDownReveal(visible = state.notes.isNotBlank() || state.isEditingNotes) {
                StudioNotesSection(
                    notes = state.notes,
                    isEditing = state.isEditingNotes,
                    editedNotes = state.editedNotes,
                    expanded = isNotesExpanded,
                    onExpandedChange = { isNotesExpanded = it },
                    onStartEditing = viewModel::startEditingNotes,
                    onEditedNotesChange = viewModel::updateEditedNotes,
                    onSave = viewModel::saveNotes,
                    onCancel = viewModel::cancelEditingNotes,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // PIPE-07: a queued/running transcription can be cancelled.
            PushDownReveal(
                visible =
                    state.status == RecordingStatus.PENDING_TRANSCRIPTION ||
                        state.status == RecordingStatus.TRANSCRIBING,
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    TextButton(onClick = viewModel::cancelTranscription) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(CoreR.string.rec_cancel_transcription))
                    }
                }
            }

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
                                imageVector = Icons.Rounded.Error,
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
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(CoreR.string.rec_retry_transcription))
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
                        val playbackTick by viewModel.playbackTick.collectAsStateWithLifecycle()
                        val activeSegmentIndex by remember {
                            derivedStateOf { playbackTick.activeTranscriptSegmentIndex }
                        }
                        TranscriptTab(
                            transcript = state.transcript,
                            effectiveTranscriptText = state.effectiveTranscriptText,
                            rawTranscriptText = state.rawTranscriptText,
                            enhancedTranscriptText = state.enhancedTranscriptText,
                            llmProcessingEnabled = state.llmProcessingEnabled,
                            transcriptDraft = state.transcriptDraft,
                            isEditingTranscript = state.isEditingTranscript,
                            hasManualCorrection = state.hasManualCorrection,
                            activeSegmentIndex = activeSegmentIndex,
                            status = state.status,
                            isSelectingTranscript = state.isSelectingTranscript,
                            renderedTranscriptText = state.renderedTranscriptText,
                            selectedTranscriptPassage = state.selectedTranscriptPassage,
                            transcriptSelectionActionInFlight = state.transcriptSelectionActionInFlight,
                            transcriptSelectionResult = state.transcriptSelectionResult,
                            onTranscriptSelectionChanged = viewModel::onTranscriptSelectionChanged,
                            onRunTranscriptSelectionAction = viewModel::runTranscriptSelectionAction,
                            onCopySelectionResult = { text ->
                                copySensitiveTextToClipboard(context, transcriptClipLabel, text)
                                viewModel.onTranscriptCopied()
                            },
                            onStartTranscription =
                                if (state.status == RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION) {
                                    { viewModel.retranscribe() }
                                } else {
                                    null
                                },
                            // sweep-04: a completed-but-empty (silence-only) transcript offers a
                            // retry instead of a dead end.
                            onRetryTranscription = { viewModel.retranscribe() },
                            onSegmentClicked = if (state.canUseTranscriptInteractions()) viewModel::onWordClicked else null,
                            onTranscriptDraftChange = viewModel::updateTranscriptDraft,
                            onCopyTranscript = {
                                val text = state.effectiveTranscriptText.trim()
                                if (text.isNotEmpty()) {
                                    copySensitiveTextToClipboard(context, transcriptClipLabel, text)
                                    viewModel.onTranscriptCopied()
                                }
                            },
                            onCopyOriginal = {
                                val text = state.rawTranscriptText.trim()
                                if (text.isNotEmpty()) {
                                    copySensitiveTextToClipboard(context, transcriptClipLabel, text)
                                    viewModel.onTranscriptCopied()
                                }
                            },
                            onCopyEnhanced = {
                                val text = state.enhancedTranscriptText.trim()
                                if (text.isNotEmpty()) {
                                    copySensitiveTextToClipboard(context, transcriptClipLabel, text)
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
                                copySensitiveTextToClipboard(context, transcriptClipLabel, item.text)
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

/**
 * NOTES: per-recording note card. Collapsed it shows the first line of the note; tapping the
 * card expands the full text in place. The pencil enters edit mode (check saves, close
 * cancels); clearing all text and saving removes the note, which hides the section again.
 */
@Composable
private fun StudioNotesSection(
    notes: String,
    isEditing: Boolean,
    editedNotes: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onStartEditing: () -> Unit,
    onEditedNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isEditing) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.StickyNote2,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.rec_note_section_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(CoreR.string.desc_cancel))
                    }
                    IconButton(onClick = onSave) {
                        Icon(Icons.Rounded.Check, contentDescription = stringResource(CoreR.string.desc_save))
                    }
                }
                val noteFieldDescription = stringResource(R.string.rec_note_field_desc)
                TextField(
                    value = editedNotes,
                    onValueChange = onEditedNotesChange,
                    placeholder = { Text(stringResource(R.string.rec_note_placeholder)) },
                    minLines = 2,
                    maxLines = 6,
                    // A11Y: name the edit box so TalkBack says what is being edited.
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = noteFieldDescription },
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                )
            }
        }
    } else {
        Surface(
            onClick = { onExpandedChange(!expanded) },
            modifier = modifier.animateContentSize(animationSpec = ChirpMotion.layoutSizeSpring),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.StickyNote2,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rec_note_section_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onStartEditing) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.rec_edit_note_desc),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.desc_collapse_note else R.string.desc_expand_note,
                        ),
                    modifier = Modifier.padding(top = 10.dp, end = 8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Studio header pills. While the duration is still unknown (an in-progress row holds 0 until
 * finalize stamps the real value — the "Stitching" window), the duration pill shows a
 * placeholder instead of a misleading "0:00" (sweep-03/04). Once stamped, this delegates to
 * the shared [MetadataPillRow] so the pills stay visually identical to the home list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioMetadataPills(
    createdAtMs: Long,
    durationMs: Long,
    source: RecordingSource,
) {
    if (durationMs > 0) {
        MetadataPillRow(
            createdAtMs = createdAtMs,
            durationMs = durationMs,
            source = source,
        )
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val today = stringResource(ContractsR.string.date_today)
            val yesterday = stringResource(ContractsR.string.date_yesterday)
            ChirpPill(
                label = remember(createdAtMs, today, yesterday) { Date(createdAtMs).formatRelative(today, yesterday) },
                icon = Icons.Filled.Schedule,
                contentDescription = stringResource(CoreR.string.rec_pill_recorded),
            )
            ChirpPill(
                label = stringResource(R.string.rec_duration_pending),
                icon = Icons.Filled.Timer,
                contentDescription = stringResource(CoreR.string.rec_pill_duration),
            )
            ChirpPill(
                label = source.label(),
                icon = source.icon(),
                contentDescription = stringResource(CoreR.string.rec_pill_source),
            )
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
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(CoreR.string.desc_navigate_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        EmptyState(
            icon = Icons.Rounded.Error,
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
