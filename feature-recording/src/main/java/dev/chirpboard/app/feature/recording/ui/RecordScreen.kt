package dev.chirpboard.app.feature.recording.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.RepositoryErrorSnackbarEffect
import dev.chirpboard.app.core.ui.haptics.ChirpHaptics
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.core.ui.theme.chirpAccents
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.session.RecoverableRecordingSession
import dev.chirpboard.app.core.ui.components.recording.AudioWaveform
import dev.chirpboard.app.core.ui.components.recording.RecordingActionRow
import dev.chirpboard.app.core.ui.components.recording.RecordingGlowBackground
import dev.chirpboard.app.core.ui.components.recording.RecordingTimer
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import dev.chirpboard.app.feature.recording.ui.tag.TagPicker
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onNavigateBack: () -> Unit,
    onRecordingComplete: (recordingId: java.util.UUID) -> Unit = { onNavigateBack() },
    autoStart: Boolean = true,
    viewModel: RecordViewModel = hiltViewModel(),
) {
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val autoStopEvent by viewModel.autoStopEvent.collectAsStateWithLifecycle()
    val sessionAdvisory by viewModel.sessionAdvisory.collectAsStateWithLifecycle()
    val lastCompletedRecordingId by viewModel.lastCompletedRecordingId.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val isProfileHandoffResolved by viewModel.isProfileHandoffResolved.collectAsStateWithLifecycle()
    val entryMessage by viewModel.entryMessage.collectAsStateWithLifecycle()
    val recoverableSessions by viewModel.recoverableSessions.collectAsStateWithLifecycle()
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
    val selectedTagIds by viewModel.selectedTagIds.collectAsStateWithLifecycle()
    val noteDraft by viewModel.noteDraft.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Note affordance expansion survives rotation/process death alongside the draft itself.
    var isNoteExpanded by rememberSaveable { mutableStateOf(false) }

    // LIF-03: dialog decisions survive rotation/resize/process death; these guard destructive
    // actions (discard, start over), so losing them mid-decision is more than cosmetic.
    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    var showRestartDialog by rememberSaveable { mutableStateOf(false) }
    var showBackDialog by rememberSaveable { mutableStateOf(false) }
    var recoveryPromptSession by remember { mutableStateOf<RecoverableRecordingSession?>(null) }
    var hasNavigatedToComplete by rememberSaveable { mutableStateOf(false) }
    // ERR-7: permanent-denial affordance — deep link into the app's settings page.
    var showMicSettingsDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val micPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.startRecording()
            } else if (isMicPermissionPermanentlyDenied(context)) {
                showMicSettingsDialog = true
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.rec_mic_permission_denied))
                }
            }
        }

    // ERR-7: every start path checks the permission in the UI layer first; a missing grant
    // re-requests instead of letting the service fail into a dead-end snackbar.
    fun startRecordingWithPermissionCheck() {
        if (isMicPermissionGranted(context)) {
            viewModel.startRecording()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val isRecording =
        recordingState is RecordingState.Recording ||
            recordingState is RecordingState.Starting ||
            recordingState is RecordingState.Stopping
    val isPaused = recordingState is RecordingState.Paused
    val isActive = isRecording || isPaused

    LaunchedEffect(recoverableSessions) {
        if (recoveryPromptSession == null) {
            recoveryPromptSession = recoverableSessions.firstOrNull()
        }
    }

    // LIF-02: the autoStart nav-argument is part of the restored back stack, so it must be
    // consumed exactly once (via SavedStateHandle) — never re-fired after process-death
    // restoration — and the decision waits for the recovery store's first refresh so the
    // empty-before-refresh window can't start the mic over a pending recovery prompt.
    val isAutoStartConsumed by viewModel.isAutoStartConsumed.collectAsStateWithLifecycle()
    val isRecoverableSessionsRefreshed by viewModel.isRecoverableSessionsRefreshed.collectAsStateWithLifecycle()
    LaunchedEffect(autoStart, isProfileHandoffResolved, isRecoverableSessionsRefreshed, isAutoStartConsumed) {
        if (
            autoStart &&
            !isAutoStartConsumed &&
            isProfileHandoffResolved &&
            isRecoverableSessionsRefreshed
        ) {
            // Decide exactly once: either start now or yield to the recovery prompt. The flag is
            // consumed either way so dismissing a recovery prompt never auto-starts the mic later.
            viewModel.consumeAutoStart()
            if (recoverableSessions.isEmpty() && recordingState is RecordingState.Idle) {
                startRecordingWithPermissionCheck()
            }
        }
    }

    // ERR-7/ERR-13: surface service-reported recording failures and reasoned auto-stops on the
    // record screen itself (previously only Home rendered RecordingState.Error).
    LaunchedEffect(recordingState) {
        val error = recordingState as? RecordingState.Error ?: return@LaunchedEffect
        viewModel.clearRecordingError()
        snackbarHostState.showSnackbar(
            message = error.message,
            duration = SnackbarDuration.Short,
        )
    }

    // ERR-13/ERR-14: reasoned auto-stops (storage critical, focus loss, device loss, capture
    // death) save through the normal stop path, so they never arrive as RecordingState.Error;
    // they surface through the service's dedicated event channel instead. Acknowledge only
    // after the snackbar ran so a navigation mid-display re-surfaces it on the next screen.
    // Stale events (older than ~5 minutes — e.g. the app was backgrounded before the snackbar
    // could run) are consumed silently so they can never greet a much later app open.
    LaunchedEffect(autoStopEvent) {
        val event = autoStopEvent ?: return@LaunchedEffect
        if (event.isStale()) {
            viewModel.consumeAutoStopEvent()
            return@LaunchedEffect
        }
        snackbarHostState.showSnackbar(
            message = event.autoStopSnackbarMessage(context),
            duration = SnackbarDuration.Short,
        )
        viewModel.consumeAutoStopEvent()
    }

    LaunchedEffect(lastCompletedRecordingId) {
        if (hasNavigatedToComplete) {
            viewModel.clearLastCompletedRecordingId()
            return@LaunchedEffect
        }
        val recordingId = lastCompletedRecordingId ?: return@LaunchedEffect
        hasNavigatedToComplete = true
        onRecordingComplete(recordingId)
        viewModel.clearLastCompletedRecordingId()
    }

    RepositoryErrorSnackbarEffect(
        errorMessage = entryMessage,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearEntryMessage,
    )

    BackHandler(enabled = isActive) {
        showBackDialog = true
    }

    fun navigateToProcessingStudioIfNeeded(recordingId: java.util.UUID?) {
        if (recordingId != null && !hasNavigatedToComplete) {
            hasNavigatedToComplete = true
            onRecordingComplete(recordingId)
        }
    }

    fun completeRecordingWithHandoff() {
        val handoffId = viewModel.stopRecordingWithHandoff()
        if (handoffId != null) {
            navigateToProcessingStudioIfNeeded(handoffId)
        } else if (isActive) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.rec_done_wait_for_id))
            }
        }
    }

    recoveryPromptSession?.let { session ->
        AnimatedAlertDialog(
            onDismissRequest = {
                viewModel.deferInterruptedSession(session.sessionId)
                recoveryPromptSession = null
            },
            title = { Text(stringResource(R.string.rec_recovery_title)) },
            text = {
                Text(
                    if (session.hasPotentialLoss) {
                        pluralStringResource(
                            R.plurals.rec_recovery_message_with_loss,
                            session.estimatedLostMinutes(),
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

    if (showMicSettingsDialog) {
        MicPermissionSettingsDialog(
            onDismiss = { showMicSettingsDialog = false },
            onOpenSettings = {
                showMicSettingsDialog = false
                openAppSettingsForPermission(context)
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
                        viewModel.cancelRecording()
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
                        viewModel.restartRecording()
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
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.recording_in_progress_text))
                    TextButton(
                        onClick = {
                            showBackDialog = false
                            onNavigateBack()
                        },
                    ) {
                        Text(stringResource(R.string.rec_browse_home))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackDialog = false
                        completeRecordingWithHandoff()
                    },
                ) {
                    Text(stringResource(R.string.save))
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
                                showBackDialog = true
                            } else {
                                onNavigateBack()
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.desc_close))
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
                .padding(horizontal = 24.dp)
                .animatePushDownLayout(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            PushDownReveal(visible = activeProfile != null) {
                activeProfile?.let { profile ->
                    ActiveProfileSessionBadge(
                        profile = profile,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            val activeRecordingId = recordingState.activeRecordingId
            PushDownReveal(visible = activeRecordingId != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.rec_tags),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TagPicker(
                        availableTags = availableTags.toImmutableList(),
                        selectedTagIds = selectedTagIds,
                        onTagToggle = viewModel::toggleTag,
                        onCreateTag = viewModel::createTagForRecording,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Low-friction live note: describe the recording while making it so you
                    // know what it is later. Collapsed to a pill next to the tags language;
                    // expands into a freeform field usable mid-capture.
                    RecordingNoteSection(
                        noteDraft = noteDraft,
                        expanded = isNoteExpanded,
                        onExpandedChange = { expanded ->
                            ChirpHaptics.tap(context)
                            isNoteExpanded = expanded
                        },
                        onNoteChange = viewModel::updateNoteDraft,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            RecordingTimer(
                recordingState = recordingState,
                isRecording = isRecording,
            )

            // A11Y: a textual Recording/Paused/Saving status with a polite live region — the
            // screen previously communicated capture state only via icon swaps and timer color,
            // which TalkBack never announces.
            val recordStatusText =
                when {
                    recordingState is RecordingState.Stopping -> stringResource(R.string.rec_record_status_saving)
                    isPaused -> stringResource(R.string.rec_record_status_paused)
                    isRecording -> stringResource(R.string.rec_record_status_recording)
                    else -> null
                }
            if (recordStatusText != null) {
                Text(
                    text = recordStatusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .padding(top = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            RecordInputDevicePicker(
                modifier = Modifier.padding(top = 12.dp),
            )

            // AUD-02/AUD-05/ERR-14: live-session advisory banner — why the session is paused
            // (focus loss), why the waveform is flat (mic silenced elsewhere), or that storage
            // is running low. Display-only twin of the notification status line; gated on an
            // active session so it can never linger after the stop resets the flags.
            PushDownReveal(visible = isActive && sessionAdvisory != null) {
                // Same Hilt-scoped instance as the inserted RecordInputDevicePicker, so the
                // silence hint names the device the session actually captures from.
                val devicePicker: InputDevicePickerViewModel = hiltViewModel()
                val activeInput by devicePicker.activeDevice.collectAsStateWithLifecycle()
                sessionAdvisory?.let { advisory ->
                    SessionAdvisoryBanner(
                        advisory = advisory,
                        activeDeviceName = activeInput?.summary?.productName,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // On-device sweep fix: the card wraps the waveform's own height (~184dp with padding)
            // and floats centered in the leftover space, instead of stretching to fill it — the
            // old weight(1f) Surface rendered as a mostly-empty box with a thin waveform band.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Box {
                        if (isRecording) {
                            RecordingGlowBackground(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(MaterialTheme.shapes.extraLarge),
                            )
                        }

                        RecordingWaveform(
                            viewModel = viewModel,
                            isRecording = isRecording,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            val canHandoffToStudio = activeRecordingId != null && isActive
            val isStopping = recordingState is RecordingState.Stopping

            RecordingActionRow(
                isRecording = isRecording,
                isPaused = isPaused,
                isStopEnabled = canHandoffToStudio,
                // An in-flight stop owns the stop gate, so a restart would be refused;
                // disable Start-over instead of letting the tap fail silently.
                isRestartEnabled = isActive && !isStopping,
                onTogglePausePlay = {
                    if (isPaused || !isActive) {
                        if (isActive) {
                            // PRM-1: resume — a record-start tick, matching the keyboard's tactile
                            // language so the two halves of the product feel identical.
                            ChirpHaptics.recordStart(context)
                            viewModel.resumeRecording()
                        } else if (isProfileHandoffResolved) {
                            ChirpHaptics.recordStart(context)
                            startRecordingWithPermissionCheck()
                        }
                    } else {
                        // Pausing is a light tap, distinct from the start tick.
                        ChirpHaptics.tap(context)
                        viewModel.pauseRecording()
                    }
                },
                onStopRecording = {
                    // PRM-1: finishing the capture — the keyboard's distinct double-tick.
                    ChirpHaptics.recordStop(context)
                    completeRecordingWithHandoff()
                },
                onRestartRecording = { showRestartDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            )
        }
    }
}

/**
 * AUD-02/AUD-05/ERR-14: inline advisory for a live session (focus pause / silenced mic /
 * low storage). Uses the shared recording-live accent pair so the banner reads as part of
 * the live-capture visual language (cohesive with the Home live row), and a polite live
 * region so TalkBack announces the condition without stealing focus.
 */
@Composable
private fun SessionAdvisoryBanner(
    advisory: RecordingSessionAdvisory,
    activeDeviceName: String?,
    modifier: Modifier = Modifier,
) {
    val accents = MaterialTheme.colorScheme.chirpAccents
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = accents.recordingLiveContainer.copy(alpha = 0.45f),
    ) {
        Text(
            text = advisory.advisoryText(LocalContext.current, activeDeviceName),
            style = MaterialTheme.typography.bodySmall,
            color = accents.recordingLive,
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
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
            // A11Y: one TalkBack stop ("Recording with profile, <emoji>, <name>") instead of
            // three unrelated nodes.
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) {},
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
                        imageVector = Icons.Rounded.Person,
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


/**
 * Live per-recording note ("describe it while you make it"). Collapsed, it reads as one more
 * chip in the tags row language — "Add note", or the note's first line once one exists — so it
 * costs no vertical space until the user wants it. Expanded, it is a freeform multiline field
 * that stays usable while capture continues; the draft itself lives in the ViewModel
 * (SavedStateHandle + debounced row write-through), so this composable is purely presentational.
 */
@Composable
private fun RecordingNoteSection(
    noteDraft: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onNoteChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.animatePushDownLayout()) {
        if (expanded) {
            val focusRequester = remember { FocusRequester() }
            val noteFieldDescription = stringResource(R.string.rec_note_field_desc)
            OutlinedTextField(
                value = noteDraft,
                onValueChange = onNoteChange,
                label = { Text(stringResource(R.string.rec_note_label)) },
                placeholder = { Text(stringResource(R.string.rec_note_placeholder)) },
                trailingIcon = {
                    IconButton(onClick = { onExpandedChange(false) }) {
                        Icon(
                            imageVector = Icons.Rounded.ExpandLess,
                            contentDescription = stringResource(R.string.desc_collapse_note),
                        )
                    }
                },
                minLines = 2,
                maxLines = 4,
                shape = ChirpShapes.Large,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // A11Y: name the edit box so TalkBack says what is being edited.
                        .semantics { contentDescription = noteFieldDescription },
            )
            LaunchedEffect(Unit) {
                // Low friction: opening an empty note goes straight to typing. A note restored
                // with content skips the grab so rotation never pops the keyboard unasked.
                if (noteDraft.isBlank()) {
                    focusRequester.requestFocus()
                }
            }
        } else {
            CollapsedNotePill(
                noteDraft = noteDraft,
                onClick = { onExpandedChange(true) },
            )
        }
    }
}

@Composable
private fun CollapsedNotePill(
    noteDraft: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstNoteLine =
        remember(noteDraft) {
            noteDraft.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        }
    Surface(
        onClick = onClick,
        modifier = modifier,
        // Mirrors the AddTagChip capsule so note + tags read as one affordance family.
        shape = ChirpShapes.Large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    if (firstNoteLine != null) {
                        Icons.AutoMirrored.Rounded.StickyNote2
                    } else {
                        Icons.Rounded.EditNote
                    },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = firstNoteLine ?: stringResource(R.string.rec_add_note_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        // PRM-2 / DECISIONS: the single shared "live/recording" accent, cohesive with the keyboard
        // glow, the home live row and the recognition dialog — not raw Material error-red.
        color = MaterialTheme.colorScheme.chirpAccents.recordingLive,
        modifier = modifier,
    )
}