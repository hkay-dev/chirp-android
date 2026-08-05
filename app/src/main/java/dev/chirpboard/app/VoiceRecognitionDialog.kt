package dev.chirpboard.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModeDefaults
import dev.chirpboard.app.core.llm.ProcessingModeListItem
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.WaveformBuffer
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.ChirpLlmToggle
import dev.chirpboard.app.core.ui.components.InputDeviceChip
import dev.chirpboard.app.core.ui.components.InputDeviceFallbackNotice
import dev.chirpboard.app.core.ui.components.InputDevicePickerUiState
import dev.chirpboard.app.core.ui.components.InputDeviceSheet
import dev.chirpboard.app.core.ui.components.ChirpVoiceTriggerButton
import dev.chirpboard.app.core.ui.components.ThinkingDots
import dev.chirpboard.app.core.ui.components.brandedPulse
import dev.chirpboard.app.core.ui.components.recording.AudioWaveform
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.core.ui.theme.chirpAccents
import dev.chirpboard.app.core.ui.theme.recordingTimerStyle
import dev.chirpboard.app.core.ui.R as CoreUiR
import dev.chirpboard.app.core.util.formatAsDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun VoiceRecognitionDialog(
    waveformBuffer: WaveformBuffer,
    sampleCountFlow: StateFlow<Long>,
    recordingStateFlow: StateFlow<RecordingState>,
    shouldDismissFlow: StateFlow<Boolean>,
    partialTranscriptFlow: StateFlow<String>,
    modelStateFlow: StateFlow<VoiceRecognitionModelState>,
    uiErrorFlow: StateFlow<VoiceRecognitionUiError?>,
    llmEnabled: Boolean,
    aiControlEnabled: Boolean,
    currentMode: ProcessingMode,
    selectableModes: List<ProcessingModeListItem>,
    callerPrompt: String?,
    englishOnlyHint: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenApp: () -> Unit,
    onDismissComplete: () -> Unit,
    onToggleLlm: (Boolean) -> Unit,
    onModeChange: (String) -> Unit,
    // AUDIODEV: compact input-device picker in the top bar. Selection applies to the
    // NEXT capture start; the host activity wires preference + live device list.
    inputDevicePicker: InputDevicePickerUiState = InputDevicePickerUiState(),
    onSelectInputDeviceAutomatic: () -> Unit = {},
    onSelectInputDevice: (AudioInputDeviceSummary) -> Unit = {},
    onRequestBluetoothNames: (() -> Unit)? = null,
) {
    val recordingState by recordingStateFlow.collectAsStateWithLifecycle(RecordingState.Idle)
    val shouldDismiss by shouldDismissFlow.collectAsStateWithLifecycle(false)
    val modelState by modelStateFlow.collectAsStateWithLifecycle(VoiceRecognitionModelState.Initializing)
    val uiError by uiErrorFlow.collectAsStateWithLifecycle(null)
    var isVisible by remember { mutableStateOf(true) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // A11Y-2: cancelling once the user's speech is captured but not yet returned (the
    // post-stop transcription window, or committed text awaiting delivery) would silently
    // discard it — an unlabeled scrim double-tap was one accidental path — so confirm
    // first. Cancel stays one-tap while idle or still recording, matching the platform
    // dialog. The transcript flow value is read imperatively so streaming text never
    // recomposes this root scope (CMP-11). Once the dismiss animation is in flight the
    // result is already committed, so late taps must not overwrite it with a cancel.
    val requestCancel: () -> Unit = {
        when {
            shouldDismiss -> Unit
            // A terminal error is already showing: there is nothing left to discard, and
            // dismissing must report that failure straight away (ERR-9/ERR-27).
            uiError != null -> onCancel()
            recordingState is RecordingState.Stopping || partialTranscriptFlow.value.isNotBlank() ->
                showDiscardConfirm = true

            else -> onCancel()
        }
    }

    // The 10 Hz waveform tick (sampleCountFlow) and the per-token partial transcript are
    // intentionally NOT collected here: collecting them at the dialog root would re-run the
    // entire content body on every tick. They are passed down as flows and collected at the
    // leaf composables that actually render them (CMP-11), matching RecordScreen/KeyboardUI.

    // Auto-start is preserved (per DECISIONS: this is a one-shot quick capture and the user's
    // intent to dictate is already explicit). Capture starts with the first composition. The
    // brief visual ready beat and recognizer warmup run alongside it, never ahead of the mic.
    var preRollComplete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        onStart()
        delay(READY_VISUAL_BEAT_MS)
        preRollComplete = true
    }

    LaunchedEffect(shouldDismiss) {
        if (shouldDismiss) {
            isVisible = false
            delay(VOICE_RECOGNITION_EXIT_MS)
            onDismissComplete()
        }
    }

    val enterTransition =
        fadeIn(animationSpec = tween(200)) +
            slideInVertically(
                initialOffsetY = { it },
                animationSpec =
                    spring(
                        dampingRatio = 0.8f,
                        stiffness = 400f,
                    ),
            )

    val exitTransition =
        fadeOut(animationSpec = tween(150)) +
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec =
                    spring(
                        dampingRatio = 0.9f,
                        stiffness = 400f,
                    ),
            )

    // The window is MATCH_PARENT (so FLAG_DIM_BEHIND can scrim the host); the host dim is drawn by
    // the window. This transparent full-height layer above the sheet captures taps on the dimmed
    // area to cancel, replacing the old FLAG_WATCH_OUTSIDE_TOUCH path that no longer fires once the
    // window covers the whole screen (DLG-5/DLG-6). The click is labeled so TalkBack does not
    // expose an anonymous full-screen "double-tap to activate" node that silently cancels (A11Y-2).
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(CoreUiR.string.desc_cancel),
                    onClick = requestCancel,
                ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = enterTransition,
            exit = exitTransition,
        ) {
            VoiceRecognitionDialogContent(
                recordingState = recordingState,
                waveformBuffer = waveformBuffer,
                sampleCountFlow = sampleCountFlow,
                partialTranscriptFlow = partialTranscriptFlow,
                modelState = modelState,
                uiError = uiError,
                preRollComplete = preRollComplete,
                llmEnabled = llmEnabled,
                aiControlEnabled = aiControlEnabled,
                currentMode = currentMode,
                selectableModes = selectableModes,
                callerPrompt = callerPrompt,
                englishOnlyHint = englishOnlyHint,
                onStart = onStart,
                onStop = onStop,
                onRetry = onRetry,
                onCancel = requestCancel,
                onOpenApp = onOpenApp,
                onToggleLlm = onToggleLlm,
                onModeChange = onModeChange,
                inputDevicePicker = inputDevicePicker,
                onSelectInputDeviceAutomatic = onSelectInputDeviceAutomatic,
                onSelectInputDevice = onSelectInputDevice,
                onRequestBluetoothNames = onRequestBluetoothNames,
            )
        }
    }

    if (showDiscardConfirm) {
        AnimatedAlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.voice_recognition_discard_title)) },
            text = { Text(stringResource(R.string.voice_recognition_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        onCancel()
                    },
                ) {
                    Text(stringResource(R.string.voice_recognition_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.voice_recognition_discard_keep))
                }
            },
        )
    }
}

@Composable
private fun VoiceRecognitionDialogContent(
    recordingState: RecordingState,
    waveformBuffer: WaveformBuffer,
    sampleCountFlow: StateFlow<Long>,
    partialTranscriptFlow: StateFlow<String>,
    modelState: VoiceRecognitionModelState,
    uiError: VoiceRecognitionUiError?,
    preRollComplete: Boolean,
    llmEnabled: Boolean,
    aiControlEnabled: Boolean,
    currentMode: ProcessingMode,
    selectableModes: List<ProcessingModeListItem>,
    callerPrompt: String?,
    englishOnlyHint: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenApp: () -> Unit,
    onToggleLlm: (Boolean) -> Unit,
    onModeChange: (String) -> Unit,
    inputDevicePicker: InputDevicePickerUiState = InputDevicePickerUiState(),
    onSelectInputDeviceAutomatic: () -> Unit = {},
    onSelectInputDevice: (AudioInputDeviceSummary) -> Unit = {},
    onRequestBluetoothNames: (() -> Unit)? = null,
) {
    val isRecording =
        recordingState is RecordingState.Recording ||
            recordingState is RecordingState.Starting ||
            recordingState is RecordingState.Stopping
    val isProcessing = recordingState is RecordingState.Stopping
    val isModelReady = modelState == VoiceRecognitionModelState.Ready
    val showRecordingVisuals = isRecording && !isProcessing && uiError == null
    val recordingVisualEnter =
        fadeIn(tween(ChirpMotion.STUDIO_REVEAL_MS, easing = FastOutSlowInEasing)) +
            expandVertically(
                animationSpec = tween(ChirpMotion.STUDIO_REVEAL_MS, easing = FastOutSlowInEasing),
            )
    val recordingVisualExit =
        fadeOut(tween(ChirpMotion.STUDIO_HIDE_MS, easing = FastOutSlowInEasing)) +
            shrinkVertically(
                animationSpec = tween(ChirpMotion.STUDIO_HIDE_MS, easing = FastOutSlowInEasing),
            )

    // Robust nav-bar inset: Samsung Good Lock can zero WindowInsets.navigationBars even while the
    // system still occupies the bottom strip, so floor the bottom pad at a minimum so the sheet's
    // content never sits flush against the very bottom edge (INS-3/INS-4).
    val density = LocalDensity.current
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val bottomInset =
        with(density) {
            maxOf(navBottomPx, DialogNavInsetFloor.roundToPx()).toDp()
        }

    // The rounded-top sheet silhouette (DialogSheetShape) plus the window dim scrim give the sheet
    // its separation from the host; a straight hairline divider would protrude past the rounded
    // corners, so the cohesion comes from the rounded sheet + inner panel instead (DLG-5/INS-2).
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                // Absorb taps on the sheet body so they do not fall through to the scrim's
                // tap-to-cancel (the window is full-screen, so the sheet is a sibling of the
                // scrim). A raw pointerInput (not a no-op clickable) so TalkBack never exposes
                // an unlabeled do-nothing "double-tap to activate" node here (A11Y-2).
                .pointerInput(Unit) { detectTapGestures { } },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        shape = DialogSheetShape,
    ) {
        // Inner rounded panel (surfaceContainer) inside the outer surfaceContainerHigh sheet, the
        // keyboard's two-layer "soft elevated panel" treatment (DLG-5). The bottom nav inset is
        // reserved here so the panel's content clears the system strip.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ChirpSpacing.Medium,
                        end = ChirpSpacing.Medium,
                        top = ChirpSpacing.Medium,
                        bottom = ChirpSpacing.Medium,
                    )
                    .padding(bottom = bottomInset)
                    .clip(ChirpShapes.KeyboardPanel)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .heightIn(min = DialogPanelMinHeight),
        ) {
            // Brand "recording/live" glow while capturing (DLG-3/VIS-8): replaces the off-brand
            // red error glow with the cohesive recordingLive accent used across all surfaces.
            // matchParentSize is load-bearing: the decorative glow must adopt the panel's
            // content-driven size without participating in its measurement. A plain
            // fillMaxSize here resolved against the full-screen window constraints
            // (MATCH_PARENT for the scrim) and ballooned the whole sheet to screen height.
            AnimatedVisibility(
                visible = showRecordingVisuals,
                modifier = Modifier.matchParentSize(),
                enter = fadeIn(tween(ChirpMotion.STUDIO_REVEAL_MS)),
                exit = fadeOut(tween(ChirpMotion.STUDIO_HIDE_MS)),
            ) {
                RecordingLiveGlow(modifier = Modifier.fillMaxSize())
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = ChirpSpacing.Medium,
                            vertical = ChirpSpacing.Small,
                        ),
            ) {
                // Top bar: AI control top-start (always visible regardless of phase, mirroring the
                // keyboard's KeyboardTopBar), close X top-end (DLG-1/DLG-2/parity).
                VoiceRecognitionTopBar(
                    llmEnabled = llmEnabled,
                    currentMode = currentMode,
                    selectableModes = selectableModes,
                    settingsEnabled = aiControlEnabled && !isProcessing,
                    onToggleLlm = onToggleLlm,
                    onModeChange = onModeChange,
                    onCancel = onCancel,
                    inputDevicePicker = inputDevicePicker.copy(sessionLive = isRecording),
                    onSelectInputDeviceAutomatic = onSelectInputDeviceAutomatic,
                    onSelectInputDevice = onSelectInputDevice,
                    onRequestBluetoothNames = onRequestBluetoothNames,
                )

                // AUDIODEV: transient "Using X — Y isn't connected" notice when the
                // preferred mic was absent at capture start (fallback was used).
                InputDeviceFallbackNotice(
                    activeDevice = inputDevicePicker.activeDevice,
                    modifier = Modifier.padding(horizontal = ChirpSpacing.Small),
                )

                // PIPE-08: the bundled model transcribes English only; say so when the caller
                // explicitly requested another language instead of returning silent garbage.
                if (englishOnlyHint) {
                    Text(
                        text = stringResource(R.string.voice_recognition_english_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .animatePushDownLayout()
                            .padding(bottom = ChirpSpacing.Medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Transcript / status area. partialTranscriptFlow is collected inside this leaf
                    // so a per-token partial update recomposes only the transcript scope (CMP-11).
                    VoiceRecognitionTranscriptArea(
                        partialTranscriptFlow = partialTranscriptFlow,
                        recordingState = recordingState,
                        modelState = modelState,
                        uiError = uiError,
                        preRollComplete = preRollComplete,
                        isRecording = isRecording,
                        isProcessing = isProcessing,
                        callerPrompt = callerPrompt,
                        onRetry = onRetry,
                        onOpenApp = onOpenApp,
                    )

                    AnimatedVisibility(
                        visible = showRecordingVisuals,
                        enter = recordingVisualEnter,
                        exit = recordingVisualExit,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            VoiceRecognitionWaveform(
                                waveformBuffer = waveformBuffer,
                                sampleCountFlow = sampleCountFlow,
                            )
                            Spacer(modifier = Modifier.height(ChirpSpacing.Large))
                        }
                    }

                    Spacer(
                        modifier =
                            Modifier.height(if (showRecordingVisuals) ChirpSpacing.Small else ChirpSpacing.ExtraExtraLarge),
                    )

                    // A terminal error replaces the mic affordance: a dead "tap to retry the
                    // exact same failure" control would mislead (ERR-9/ERR-23).
                    if (uiError == null) {
                        VoiceRecognitionMicControl(
                            isRecording = isRecording,
                            isProcessing = isProcessing,
                            isModelReady = isModelReady,
                            onStart = onStart,
                            onStop = onStop,
                        )
                    } else {
                        Spacer(modifier = Modifier.size(MicControlAreaSize))
                    }

                    Spacer(modifier = Modifier.height(ChirpSpacing.ExtraLarge))
                }
            }
        }
    }
}

/**
 * Top bar mirroring the keyboard's [KeyboardTopBar]: the shared [ChirpLlmToggle] sparkle (which
 * opens a mode-selector dropdown) at the start, the close X at the end. Living in a fixed top bar
 * keeps the AI control visible in every phase — it can never be pushed below the sheet and clipped
 * the way the old bottom chip was while recording (DLG-1/DLG-2).
 */
@Composable
private fun VoiceRecognitionTopBar(
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    selectableModes: List<ProcessingModeListItem>,
    settingsEnabled: Boolean,
    onToggleLlm: (Boolean) -> Unit,
    onModeChange: (String) -> Unit,
    onCancel: () -> Unit,
    inputDevicePicker: InputDevicePickerUiState = InputDevicePickerUiState(),
    onSelectInputDeviceAutomatic: () -> Unit = {},
    onSelectInputDevice: (AudioInputDeviceSummary) -> Unit = {},
    onRequestBluetoothNames: (() -> Unit)? = null,
) {
    var deviceSheetOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.ExtraSmall),
        ) {
            VoiceRecognitionAiControl(
                llmEnabled = llmEnabled,
                currentMode = currentMode,
                selectableModes = selectableModes,
                enabled = settingsEnabled,
                onToggleLlm = onToggleLlm,
                onModeChange = onModeChange,
            )

            // AUDIODEV: the compact device chip — shows the mic this capture is using
            // (or the one the next capture will use) and opens the shared device sheet.
            InputDeviceChip(
                state = inputDevicePicker,
                onClick = { deviceSheetOpen = true },
                modifier = Modifier.widthIn(max = DialogDeviceChipMaxWidth),
            )
        }

        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(CoreUiR.string.desc_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (deviceSheetOpen) {
        InputDeviceSheet(
            state = inputDevicePicker,
            onSelectAutomatic = onSelectInputDeviceAutomatic,
            onSelectDevice = onSelectInputDevice,
            onRequestBluetoothNames = onRequestBluetoothNames,
            onDismiss = { deviceSheetOpen = false },
        )
    }
}

/**
 * The shared AI/LLM affordance for the dialog (DLG-1/VIS-2/DLG-7): the same [ChirpLlmToggle]
 * sparkle the keyboard uses, opening a [DropdownMenu] that toggles AI and lets the user pick the
 * processing mode — the keyboard's [KeyboardAiSettingsMenu] pattern, so the two surfaces present
 * the AI control identically. Modes are selectable only when AI is enabled, matching the keyboard.
 */
@Composable
private fun VoiceRecognitionAiControl(
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    selectableModes: List<ProcessingModeListItem>,
    enabled: Boolean,
    onToggleLlm: (Boolean) -> Unit,
    onModeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modes = selectableModes.ifEmpty { defaultDialogModeOptions() }
    LaunchedEffect(enabled) {
        if (!enabled) {
            expanded = false
        }
    }

    Box {
        ChirpLlmToggle(
            enabled = llmEnabled,
            onClick = { if (enabled) expanded = true },
            contentDescription = stringResource(R.string.voice_recognition_ai_settings),
            interactionEnabled = enabled,
            onStateDescription = stringResource(R.string.voice_recognition_ai_state_on),
            offStateDescription = stringResource(R.string.voice_recognition_ai_state_off),
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (llmEnabled) {
                            stringResource(R.string.voice_recognition_ai_disable)
                        } else {
                            stringResource(R.string.voice_recognition_ai_enable)
                        },
                    )
                },
                onClick = {
                    if (enabled) {
                        onToggleLlm(!llmEnabled)
                        expanded = false
                    }
                },
                enabled = enabled,
                leadingIcon = {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                },
                trailingIcon = {
                    if (llmEnabled) {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                    }
                },
            )

            HorizontalDivider()

            modes.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    enabled = enabled && llmEnabled,
                    onClick = {
                        if (enabled && llmEnabled) {
                            onModeChange(option.id)
                            expanded = false
                        }
                    },
                    leadingIcon =
                        if (currentMode.id == option.id) {
                            { Icon(Icons.Rounded.Check, contentDescription = null) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

/** Built-in mode list, used when the port has not yet emitted its selectable modes. */
private fun defaultDialogModeOptions(): List<ProcessingModeListItem> =
    ProcessingModeDefaults.builtInSelectableIds.map { id ->
        ProcessingModeListItem(id = id, name = ProcessingModeDefaults.displayName(id))
    }

/**
 * The mic affordance. Idle/ready uses the shared [ChirpVoiceTriggerButton] (the same FAB the
 * keyboard idle uses, DLG-MIC); recording uses a recordingLive-tinted FAB with the keyboard's
 * [RecordingActionsRow] stop-pulse so the live button breathes. During model load the idle FAB is
 * masked with a [brandedPulse] instead of a dead grey static mic (DLG-3/LOAD-5).
 */
@Composable
private fun VoiceRecognitionMicControl(
    isRecording: Boolean,
    isProcessing: Boolean,
    isModelReady: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val recordingLiveContainer = MaterialTheme.colorScheme.chirpAccents.recordingLiveContainer
    val onRecordingLiveContainer = MaterialTheme.colorScheme.chirpAccents.onRecordingLiveContainer
    val containerColor by animateColorAsState(
        targetValue =
            if (isRecording && !isProcessing) {
                recordingLiveContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        animationSpec = tween(durationMillis = 300, easing = EaseInOut),
        label = "mic_container_color",
    )
    val contentColor by animateColorAsState(
        targetValue =
            if (isRecording && !isProcessing) {
                onRecordingLiveContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        animationSpec = tween(durationMillis = 300, easing = EaseInOut),
        label = "mic_content_color",
    )

    Box(
        modifier = Modifier.size(MicControlAreaSize),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isProcessing ->
                Box(
                    modifier = Modifier.size(MicSize),
                    contentAlignment = Alignment.Center,
                ) {
                    ThinkingDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

            isRecording -> {
                val infiniteTransition = rememberInfiniteTransition(label = "recordingStopPulse")
                val stopPulse =
                    infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.06f,
                        animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOutQuad), RepeatMode.Reverse),
                        label = "stopPulse",
                    )
                FloatingActionButton(
                    onClick = onStop,
                    modifier =
                        Modifier
                            .size(MicSize)
                            .graphicsLayer {
                                scaleX = stopPulse.value
                                scaleY = stopPulse.value
                            },
                    containerColor = containerColor,
                    contentColor = contentColor,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                ) {
                    Icon(
                        Icons.Rounded.Stop,
                        contentDescription = stringResource(R.string.desc_stop),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            else ->
                // Idle/ready (or still loading): the shared trigger button, pulsing while the model
                // is not yet ready so the wait reads "warming" rather than a dead grey mic.
                ChirpVoiceTriggerButton(
                    onClick = onStart,
                    contentDescription = stringResource(R.string.desc_start),
                    modifier = if (isModelReady) Modifier else Modifier.brandedPulse(),
                    size = MicSize,
                    iconSize = 32.dp,
                    containerColor = containerColor,
                    contentColor = contentColor,
                )
        }
    }
}

private const val VOICE_RECOGNITION_EXIT_MS = 250L
private const val TRANSCRIPT_CROSSFADE_MS = 200

/** Calm visual "ready to listen" beat while capture starts (DLG-4/LOAD-5). */
private const val READY_VISUAL_BEAT_MS = 300L

private val MicSize = 64.dp

/** Footprint reserved for the mic affordance so error states keep the sheet's layout stable. */
private val MicControlAreaSize = 96.dp

/** Max width of the top-bar device chip so long Bluetooth names ellipsize, not push the close X. */
private val DialogDeviceChipMaxWidth = 168.dp

/** Minimum sheet bottom inset so content never sits flush against the edge when Good Lock zeroes it. */
private val DialogNavInsetFloor = 16.dp

/** Minimum height of the inner panel so a short status line does not collapse the sheet. */
private val DialogPanelMinHeight = 200.dp

/** Minimum (growable) height of the transcript/status area; fixed 80dp clipped at large font scales. */
private val TranscriptAreaMinHeight = 80.dp

/** Bottom-sheet silhouette: rounded TOP corners so the sheet reads as rising from the bottom (INS-2). */
private val DialogSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** The kind of content shown in the transcript area; the crossfade keys on this, not the text. */
internal enum class TranscriptAreaKind {
    Error,
    Transcript,
    ModelLoading,
    ModelUnavailable,
    Ready,
    Timer,
    Processing,
    Empty,
}

internal fun transcriptAreaKind(
    hasText: Boolean,
    hasError: Boolean,
    modelState: VoiceRecognitionModelState,
    preRollComplete: Boolean,
    isRecording: Boolean,
    isProcessing: Boolean,
): TranscriptAreaKind =
    when {
        // A terminal error owns the area: failures must be explained, never a silent close
        // (ERR-9/ERR-23/ERR-27). Errors only arise on paths that produced no text.
        hasError -> TranscriptAreaKind.Error
        hasText -> TranscriptAreaKind.Transcript
        // The visual beat never delays capture or recognizer loading.
        !preRollComplete -> TranscriptAreaKind.Ready
        modelState == VoiceRecognitionModelState.Initializing -> TranscriptAreaKind.ModelLoading
        modelState == VoiceRecognitionModelState.Unavailable -> TranscriptAreaKind.ModelUnavailable
        // A11Y-8: the post-stop phase shows a textual status, not a blank area with bare dots.
        isProcessing -> TranscriptAreaKind.Processing
        isRecording -> TranscriptAreaKind.Timer
        else -> TranscriptAreaKind.Empty
    }

/**
 * Transcript / status area. Collects the streaming partial transcript at this leaf so a
 * per-token update recomposes only here (CMP-11), and keys the crossfade on the content
 * *kind* so streamed words update a plain [Text] in place instead of ghost-crossfading the
 * whole paragraph against itself on every token (UI-16).
 */
@Composable
private fun VoiceRecognitionTranscriptArea(
    partialTranscriptFlow: StateFlow<String>,
    recordingState: RecordingState,
    modelState: VoiceRecognitionModelState,
    uiError: VoiceRecognitionUiError?,
    preRollComplete: Boolean,
    isRecording: Boolean,
    isProcessing: Boolean,
    callerPrompt: String?,
    onRetry: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val partialTranscript by partialTranscriptFlow.collectAsStateWithLifecycle("")
    val kind =
        transcriptAreaKind(
            hasText = partialTranscript.isNotBlank(),
            hasError = uiError != null,
            modelState = modelState,
            preRollComplete = preRollComplete,
            isRecording = isRecording,
            isProcessing = isProcessing,
        )
    // A11Y-1: every status transition in here is the dialog's only textual feedback, so
    // each status text is a polite live region. The streaming transcript itself is not
    // (per-token announcements would be unusable chatter).
    val statusLiveRegion = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                // Min (not fixed) height so large font scales grow the area instead of
                // clipping the only textual feedback in the dialog (A11Y fontscale).
                .heightIn(min = TranscriptAreaMinHeight)
                .padding(horizontal = ChirpSpacing.Small),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = kind,
            transitionSpec = {
                fadeIn(animationSpec = tween(TRANSCRIPT_CROSSFADE_MS)) togetherWith
                    fadeOut(animationSpec = tween(TRANSCRIPT_CROSSFADE_MS))
            },
            label = "transcript_animation",
        ) { targetKind ->
            when (targetKind) {
                TranscriptAreaKind.Error ->
                    VoiceRecognitionErrorStatus(
                        uiError = uiError,
                        statusLiveRegion = statusLiveRegion,
                        onRetry = onRetry,
                        onOpenApp = onOpenApp,
                    )

                TranscriptAreaKind.Transcript ->
                    Text(
                        // Read the live value so words stream in place within this kind
                        // without restarting the crossfade.
                        text = partialTranscript,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )

                TranscriptAreaKind.ModelLoading ->
                    Text(
                        text = stringResource(R.string.voice_recognition_model_loading),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = statusLiveRegion,
                    )

                TranscriptAreaKind.ModelUnavailable ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.voice_recognition_model_unavailable),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = statusLiveRegion,
                        )
                        Spacer(modifier = Modifier.height(ChirpSpacing.Small))
                        // ERR-10: "Open Chirp to download it" needs a tap action, not just prose.
                        FilledTonalButton(onClick = onOpenApp) {
                            Text(stringResource(R.string.voice_recognition_open_app))
                        }
                    }

                TranscriptAreaKind.Ready ->
                    Text(
                        // IME-15: show the caller's instructional prompt ("Say your search")
                        // when one was provided.
                        text = callerPrompt ?: stringResource(R.string.voice_recognition_ready),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = statusLiveRegion,
                    )

                TranscriptAreaKind.Timer ->
                    VoiceRecognitionTimer(recordingState = recordingState)

                TranscriptAreaKind.Processing ->
                    Text(
                        text = stringResource(R.string.voice_recognition_processing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = statusLiveRegion,
                    )

                TranscriptAreaKind.Empty -> Unit
            }
        }
    }
}

/**
 * Terminal in-dialog error status (ERR-9/ERR-23/ERR-27): explains the failure where the
 * sheet previously just vanished. The permission state carries an "Open Chirp" affordance
 * since an IME-less dialog cannot request the grant itself. The no-speech timeout is a
 * gentle state, not a failure: neutral color and a "Try again" affordance instead of an
 * abrupt close (the error code is returned only if the user dismisses without retrying).
 */
@Composable
private fun VoiceRecognitionErrorStatus(
    uiError: VoiceRecognitionUiError?,
    statusLiveRegion: Modifier,
    onRetry: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val message =
        when (uiError) {
            null -> return
            VoiceRecognitionUiError.PermissionMissing ->
                stringResource(R.string.voice_recognition_error_permission)

            is VoiceRecognitionUiError.MicBusy ->
                stringResource(R.string.voice_recognition_error_busy, uiError.sourceLabel)

            VoiceRecognitionUiError.CaptureFailed ->
                stringResource(R.string.voice_recognition_error_capture_failed)

            is VoiceRecognitionUiError.TranscriptionFailed ->
                if (uiError.audioRescued) {
                    stringResource(R.string.voice_recognition_error_transcription_rescued)
                } else {
                    stringResource(R.string.voice_recognition_error_transcription)
                }

            VoiceRecognitionUiError.NoSpeech ->
                stringResource(R.string.voice_recognition_error_no_speech)

            VoiceRecognitionUiError.NoSpeechTimeout ->
                stringResource(R.string.voice_recognition_no_speech_timeout)
        }
    val gentle = uiError == VoiceRecognitionUiError.NoSpeechTimeout
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (gentle) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            textAlign = TextAlign.Center,
            modifier = statusLiveRegion,
        )
        if (gentle) {
            Spacer(modifier = Modifier.height(ChirpSpacing.Small))
            FilledTonalButton(onClick = onRetry) {
                Text(stringResource(R.string.voice_recognition_try_again))
            }
        }
        if (uiError == VoiceRecognitionUiError.PermissionMissing) {
            Spacer(modifier = Modifier.height(ChirpSpacing.Small))
            FilledTonalButton(onClick = onOpenApp) {
                Text(stringResource(R.string.voice_recognition_open_app))
            }
        }
    }
}

/**
 * Calm recording timer (DLG-3/DLG-8/VIS-8): a compact variant of the shared [recordingTimerStyle]
 * timer token, tinted with the brand recordingLive accent (not raw error red) and without the loud
 * all-caps "DURATION" caption — a quiet "we are listening" indicator, not a stopwatch. Renders the
 * duration locally because the shared RecordingTimer hardcodes the red color + caption.
 */
@Composable
private fun VoiceRecognitionTimer(recordingState: RecordingState) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var previousSegmentsMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is RecordingState.Starting -> {
                previousSegmentsMs = 0L
                elapsedMs = 0L
            }

            is RecordingState.Recording -> {
                val segmentStart = state.startTimeMs
                while (true) {
                    val raw = previousSegmentsMs + (System.currentTimeMillis() - segmentStart)
                    elapsedMs = raw - (raw % MILLIS_PER_SECOND)
                    delay(ChirpMotion.TIMER_TICK_MS)
                }
            }

            is RecordingState.Paused -> {
                previousSegmentsMs = state.accumulatedMs
                elapsedMs = state.accumulatedMs
            }

            is RecordingState.Idle -> {
                previousSegmentsMs = 0L
                elapsedMs = 0L
            }

            else -> Unit
        }
    }

    // A11Y-1: announce that capture is live when the timer appears. The description is a
    // constant "Listening…" (not the ticking digits) so TalkBack hears the state change
    // once instead of chatty per-second updates.
    val listeningDescription = stringResource(R.string.voice_recognition_listening)
    Text(
        text = elapsedMs.formatAsDuration(),
        // A restrained compact size of the same Light/tnum family as the shared timer token, so the
        // dialog timer and the in-app recorder read as one family rather than a one-off monospace.
        style = recordingTimerStyle.copy(fontSize = DIALOG_TIMER_FONT_SIZE),
        color = MaterialTheme.colorScheme.chirpAccents.recordingLive,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = listeningDescription
            },
    )
}

private const val MILLIS_PER_SECOND = 1000L

/** Compact recording-timer size: a calm counterpart to the 72sp in-app recorder timer. */
private val DIALOG_TIMER_FONT_SIZE = 40.sp

/**
 * Waveform leaf. Collects the 10 Hz sample-count tick here so amplitude updates recompose
 * only the waveform scope, not the whole dialog content (CMP-11). Uses the brand recordingLive
 * accent and hides the idle placeholder so the resting state reads as a calm baseline rather than
 * a row of alarming red dots (DLG-3/VIS-8 / waveform polish).
 */
@Composable
private fun VoiceRecognitionWaveform(
    waveformBuffer: WaveformBuffer,
    sampleCountFlow: StateFlow<Long>,
) {
    val sampleCount by sampleCountFlow.collectAsStateWithLifecycle(0L)
    AudioWaveform(
        waveformBuffer = waveformBuffer,
        sampleCount = sampleCount,
        isActive = true,
        color = MaterialTheme.colorScheme.chirpAccents.recordingLive,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ChirpSpacing.ExtraLarge),
        maxBarHeight = 48.dp,
        showIdlePlaceholder = false,
    )
}

/**
 * Brand "recording/live" glow behind the recording content (DLG-3/VIS-8). A local copy of the
 * vertical-gradient glow tinted with the cohesive recordingLive accent instead of the shared red
 * [RecordingGlowBackground], so the dialog's recording state reads on-brand.
 */
@Composable
private fun RecordingLiveGlow(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "recordingLiveGlow")
    val glow =
        transition.animateFloat(
            initialValue = GLOW_MID_ALPHA,
            targetValue = GLOW_PEAK_ALPHA,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(GLOW_TWEEN_MS, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "recordingLiveGlowAlpha",
        )
    val accent = MaterialTheme.colorScheme.chirpAccents.recordingLive
    Canvas(modifier = modifier) {
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color.Transparent,
                            accent.copy(alpha = glow.value * 0.5f),
                            accent.copy(alpha = glow.value),
                        ),
                    startY = 0f,
                    endY = size.height,
                ),
        )
    }
}

private const val GLOW_TWEEN_MS = 1200
private const val GLOW_MID_ALPHA = 0.10f
private const val GLOW_PEAK_ALPHA = 0.22f
