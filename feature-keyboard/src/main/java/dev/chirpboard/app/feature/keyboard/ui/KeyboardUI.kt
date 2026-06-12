package dev.chirpboard.app.feature.keyboard.ui

import android.os.SystemClock

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModeListItem
import dev.chirpboard.app.core.recording.WaveformBuffer
import dev.chirpboard.app.core.ui.components.ChirpLlmToggle
import dev.chirpboard.app.core.ui.components.ChirpVoiceTriggerButton
import dev.chirpboard.app.core.ui.components.ThinkingDots
import dev.chirpboard.app.core.ui.components.brandedPulse
import dev.chirpboard.app.core.ui.components.recording.AudioWaveform
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.core.ui.theme.chirpAccents
import dev.chirpboard.app.feature.keyboard.R
import dev.chirpboard.app.feature.keyboard.haptic.HapticFeedback
import dev.chirpboard.app.feature.keyboard.session.KeyboardUiState
import dev.chirpboard.app.feature.keyboard.session.ModelBannerState
import dev.chirpboard.app.feature.keyboard.session.VoicePanelPhase
import dev.chirpboard.app.feature.keyboard.session.isWarming
import dev.chirpboard.app.feature.keyboard.session.requiresActionBanner
import dev.chirpboard.app.feature.keyboard.theme.KeyboardTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

private val KeyboardPanelShape = ChirpShapes.KeyboardPanel
private val RecordingActionsHeight = 64.dp
private val ModelBannerMinHeight = 44.dp
private const val VoiceTransitionMs = 280
private val SpaceCursorDragStep = 10.dp

/**
 * Minimum bottom clearance reserved under the keyboard for the system IME-nav strip (INS-1).
 *
 * Samsung draws an IME-switcher glyph + a collapse-keyboard chevron along the very bottom edge of
 * the IME window. With Good Lock hiding the gesture-nav hint, `WindowInsets.navigationBars` /
 * `systemGestures` can report 0, so a naive inset would leave those system buttons overlapping the
 * backspace/Space keys. This floor guarantees clearance regardless; the dynamic system inset (when
 * present, e.g. a 3-button nav bar) is taken as the larger of the two.
 */
private val KeyboardImeNavMinStrip = 30.dp

/**
 * Subtle shadow lifting the keyboard panel off the host app content (INS-8).
 *
 * The IME window sits directly atop the host's content with only a 1dp top divider; a small
 * shadowElevation gives the panel a gentle drop shadow along its top edge so it reads as a floating
 * surface rather than a flat seam, without the heavy lift of a dialog.
 */
private val KeyboardTopShadowElevation = 6.dp

internal fun shouldStartSpaceCursorDrag(
    dx: Float,
    dy: Float,
    thresholdPx: Float,
): Boolean = abs(dx) > thresholdPx && abs(dx) > abs(dy)

/**
 * Resolve the keyboard's reserved bottom inset (in px) as the max of the dispatched system insets
 * and the [minStripPx] floor (INS-1). Pure so the Good-Lock-zeroing edge case is unit-testable.
 *
 * @param navBarsBottomPx WindowInsets.navigationBars bottom (0 when Good Lock hides the hint).
 * @param systemGesturesBottomPx WindowInsets.systemGestures bottom (also 0 under Good Lock here).
 * @param minStripPx the [KeyboardImeNavMinStrip] floor in px.
 */
internal fun resolveKeyboardBottomInsetPx(
    navBarsBottomPx: Int,
    systemGesturesBottomPx: Int,
    minStripPx: Int,
): Int = maxOf(navBarsBottomPx, systemGesturesBottomPx, minStripPx)

internal fun isPointerInsideKey(
    position: Offset,
    width: Int,
    height: Int,
): Boolean =
    position.x >= 0f &&
        position.y >= 0f &&
        position.x <= width.toFloat() &&
        position.y <= height.toFloat()

private enum class ProcessingPhase {
    Transcribing,
    Polishing,
}

/** What the centered keyboard panel box should show, used to crossfade error <-> panel (UI-3). */
internal sealed interface KeyboardPanelContent {
    data class ErrorOverlay(val message: String) : KeyboardPanelContent

    data class LlmError(val message: String) : KeyboardPanelContent

    data class RecognitionError(val message: String) : KeyboardPanelContent

    data object Panel : KeyboardPanelContent
}

/** Stable discriminator so the crossfade only runs on error<->panel kind changes, not on the */
/** panel's own internal Idle/Recording/Loading sub-transitions. */
internal enum class KeyboardPanelContentKind {
    ErrorOverlay,
    LlmError,
    RecognitionError,
    Panel,
}

internal fun KeyboardPanelContent.kind(): KeyboardPanelContentKind =
    when (this) {
        is KeyboardPanelContent.ErrorOverlay -> KeyboardPanelContentKind.ErrorOverlay
        is KeyboardPanelContent.LlmError -> KeyboardPanelContentKind.LlmError
        is KeyboardPanelContent.RecognitionError -> KeyboardPanelContentKind.RecognitionError
        KeyboardPanelContent.Panel -> KeyboardPanelContentKind.Panel
    }

internal fun resolveKeyboardPanelContent(
    errorOverlay: String?,
    voicePanel: VoicePanelPhase,
    errorMessage: String?,
    llmErrorMessage: String?,
): KeyboardPanelContent =
    when {
        errorOverlay != null -> KeyboardPanelContent.ErrorOverlay(errorOverlay)
        voicePanel == VoicePanelPhase.LlmError && llmErrorMessage != null ->
            KeyboardPanelContent.LlmError(llmErrorMessage)
        voicePanel == VoicePanelPhase.Error && errorMessage != null ->
            KeyboardPanelContent.RecognitionError(errorMessage)
        else -> KeyboardPanelContent.Panel
    }

private fun VoicePanelPhase.processingPhase(): ProcessingPhase? =
    when (this) {
        VoicePanelPhase.Transcribing -> ProcessingPhase.Transcribing
        VoicePanelPhase.Polishing -> ProcessingPhase.Polishing
        else -> null
    }

private data class KeyboardModeOption(
    val id: String,
    @StringRes val labelRes: Int,
)

private fun defaultKeyboardModeOptions(): List<ProcessingModeListItem> =
    keyboardModeOptions().map { option ->
        ProcessingModeListItem(
            id = option.id,
            name = when (option.id) {
                "proofread" -> "Proofread"
                "formal" -> "Formal"
                "casual" -> "Casual"
                "email" -> "Email"
                "code" -> "Code"
                "smart" -> "Smart"
                else -> option.id
            },
        )
    }

private fun keyboardModeOptions(): List<KeyboardModeOption> =
    listOf(
        KeyboardModeOption("proofread", R.string.keyboard_mode_proofread),
        KeyboardModeOption("formal", R.string.keyboard_mode_formal),
        KeyboardModeOption("casual", R.string.keyboard_mode_casual),
        KeyboardModeOption("email", R.string.keyboard_mode_email),
        KeyboardModeOption("code", R.string.keyboard_mode_code),
        KeyboardModeOption("smart", R.string.keyboard_mode_smart),
    )

@Composable
fun KeyboardScreen(
    uiState: KeyboardUiState,
    waveformBuffer: WaveformBuffer,
    sampleCountFlow: StateFlow<Long>,
    onMicTap: () -> Unit,
    onCancel: () -> Unit = {},
    onRestart: () -> Unit = {},
    onToggleLlm: () -> Unit,
    onModeChange: (String) -> Unit,
    onBackspace: () -> Unit = {},
    onBackspaceWord: () -> Unit = {},
    onSpace: () -> Unit = {},
    onMoveCursor: (Int) -> Unit = {},
    onOpenApp: () -> Unit = {},
    onDismissError: () -> Unit = {},
) {
    KeyboardTheme {
        val outlineColor = MaterialTheme.colorScheme.outlineVariant
        val voicePhase = uiState.voicePanel
        val recordingVisual by animateFloatAsState(
            targetValue = if (uiState.showRecordingActions) 1f else 0f,
            animationSpec = tween(VoiceTransitionMs, easing = FastOutSlowInEasing),
            label = "recordingActionsVisual",
        )

        // INS-1: reserve a bottom inset for the system IME-nav strip so Samsung's IME-switcher +
        // collapse buttons no longer overlap backspace/Space. Floor it with KeyboardImeNavMinStrip
        // because Good Lock can zero the gesture inset; take the larger of that and the dispatched
        // system insets (a real 3-button nav bar reports more).
        val density = LocalDensity.current
        val navBarsBottomPx = WindowInsets.navigationBars.getBottom(density)
        val systemGesturesBottomPx = WindowInsets.systemGestures.getBottom(density)
        val bottomInset =
            with(density) {
                resolveKeyboardBottomInsetPx(
                    navBarsBottomPx = navBarsBottomPx,
                    systemGesturesBottomPx = systemGesturesBottomPx,
                    minStripPx = KeyboardImeNavMinStrip.roundToPx(),
                ).toDp()
            }

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 284.dp + bottomInset, max = 320.dp + bottomInset)
                    .drawBehind {
                        drawLine(
                            color = outlineColor,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = KeyboardTopShadowElevation,
        ) {
            KeyboardMainPanel(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .padding(bottom = bottomInset),
            ) {
                KeyboardTopBar(
                    uiState = uiState,
                    onToggleLlm = onToggleLlm,
                    onModeChange = onModeChange,
                )

                // KBD-2: only the actionable banners (NotDownloaded / InitFailed) appear as a
                // text banner. The "warming into RAM" (Initializing) case is masked on the mic
                // itself (shimmer/pulse) instead of an abrupt progress-bar banner below the toggle.
                val bannerVisible =
                    uiState.modelBanner.requiresActionBanner() && voicePhase == VoicePanelPhase.Idle
                // Latch the last actionable banner so the shrink/fade-out exit still has content to
                // animate when the banner is cleared, instead of blanking in one frame.
                var lastBanner by remember { mutableStateOf(uiState.modelBanner) }
                if (uiState.modelBanner.requiresActionBanner()) {
                    lastBanner = uiState.modelBanner
                }
                // KBD-2/KBD-3: the model is loading into memory but the files are present — mask
                // the wait as a calm shimmer/pulse on the idle mic rather than a progress bar.
                val modelWarming = uiState.modelBanner.isWarming() && voicePhase == VoicePanelPhase.Idle
                AnimatedVisibility(
                    visible = bannerVisible,
                    enter = expandVertically(animationSpec = tween(VoiceTransitionMs)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = tween(VoiceTransitionMs)) + fadeOut(),
                ) {
                    KeyboardModelBanner(
                        modelBanner = lastBanner,
                        initFailedMessage = uiState.modelInitFailedMessage,
                        onOpenApp = onOpenApp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }

                val panelContent =
                    resolveKeyboardPanelContent(
                        errorOverlay = uiState.errorOverlay,
                        voicePanel = voicePhase,
                        errorMessage = uiState.errorMessage,
                        llmErrorMessage = uiState.llmErrorMessage,
                    )

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Crossfade error<->panel so entering/leaving an error animates like every other
                    // panel state. Keyed on the content kind so the panel's own internal
                    // Idle/Recording/Loading sub-transitions are not re-crossfaded here (UI-3).
                    AnimatedContent(
                        targetState = panelContent,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            fadeIn(tween(VoiceTransitionMs, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(VoiceTransitionMs, easing = FastOutSlowInEasing))
                        },
                        contentAlignment = Alignment.Center,
                        contentKey = { it.kind() },
                        label = "keyboardPanelContent",
                    ) { content ->
                        when (content) {
                            is KeyboardPanelContent.ErrorOverlay ->
                                ErrorContent(content.message, onDismissError)

                            is KeyboardPanelContent.LlmError ->
                                LlmErrorContent(content.message, onDismissError)

                            is KeyboardPanelContent.RecognitionError ->
                                ErrorContent(content.message, onMicTap)

                            KeyboardPanelContent.Panel ->
                                UnifiedVoicePanel(
                                    phase = voicePhase,
                                    recordingVisual = recordingVisual,
                                    modelLoadProgress = uiState.modelLoadProgress,
                                    modelWarming = modelWarming,
                                    waveformBuffer = waveformBuffer,
                                    sampleCountFlow = sampleCountFlow,
                                    onStart = onMicTap,
                                )
                        }
                    }
                }

                if (uiState.showTypingControls) {
                    if (recordingVisual > 0.01f) {
                        RecordingActionsRow(
                            visibility = recordingVisual,
                            onStop = onMicTap,
                            onCancel = onCancel,
                            onRestart = onRestart,
                            modifier = Modifier.fillMaxWidth().zIndex(1f),
                        )
                    }

                    KeyboardControls(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .zIndex(2f),
                        onBackspace = onBackspace,
                        onBackspaceWord = onBackspaceWord,
                        onSpace = onSpace,
                        onMoveCursor = onMoveCursor,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyboardMainPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .clip(KeyboardPanelShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
private fun KeyboardTopBar(
    uiState: KeyboardUiState,
    onToggleLlm: () -> Unit,
    onModeChange: (String) -> Unit,
) {
    val statusLabelRes = uiState.statusLabelRes()

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.height(20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Crossfade(
                targetState = statusLabelRes ?: 0,
                animationSpec = tween(VoiceTransitionMs, easing = FastOutSlowInEasing),
                label = "keyboardStatusLabel",
            ) { labelRes ->
                if (labelRes != 0) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        KeyboardAiSettingsMenu(
            llmEnabled = uiState.llmEnabled,
            currentMode = uiState.processingMode,
            availableModes = uiState.availableModes,
            enabled = uiState.settingsEnabled,
            onToggleLlm = onToggleLlm,
            onModeChange = onModeChange,
        )
    }
}

@Composable
private fun KeyboardAiSettingsMenu(
    llmEnabled: Boolean,
    currentMode: ProcessingMode,
    availableModes: List<ProcessingModeListItem>,
    enabled: Boolean,
    onToggleLlm: () -> Unit,
    onModeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modes = availableModes.ifEmpty { defaultKeyboardModeOptions() }
    LaunchedEffect(enabled) {
        if (!enabled) {
            expanded = false
        }
    }


    Box {
        ChirpLlmToggle(
            enabled = llmEnabled,
            onClick = { if (enabled) expanded = true },
            contentDescription = stringResource(R.string.keyboard_ai_settings),
            interactionEnabled = enabled,
            onStateDescription = stringResource(R.string.keyboard_ai_state_on),
            offStateDescription = stringResource(R.string.keyboard_ai_state_off),
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (llmEnabled) {
                            stringResource(R.string.keyboard_ai_disable)
                        } else {
                            stringResource(R.string.keyboard_ai_enable)
                        },
                    )
                },
                onClick = {
                    if (enabled) {
                        onToggleLlm()
                        expanded = false
                    }
                },
                enabled = enabled,
                leadingIcon = {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                },
                trailingIcon = {
                    if (llmEnabled) {
                        Icon(Icons.Filled.Check, contentDescription = null)
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
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun KeyboardModelBanner(
    modelBanner: ModelBannerState,
    initFailedMessage: String?,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // KBD-2: only the actionable banners reach here; the "warming into RAM" (Initializing) state
    // is masked on the mic affordance, never as a banner. None/Initializing render nothing.
    if (!modelBanner.requiresActionBanner()) {
        return
    }
    // Both actionable variants share one container (same background, shape and min-height) so
    // swapping between NotDownloaded/InitFailed only changes the inner content rather than
    // reflowing or flashing a differently-styled component below the mic FAB (UI-4).
    Row(
        modifier =
            modifier
                .clip(ChirpShapes.Small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .heightIn(min = ModelBannerMinHeight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (modelBanner) {
            ModelBannerState.None,
            ModelBannerState.Initializing,
            -> Unit

            ModelBannerState.NotDownloaded -> {
                Text(
                    stringResource(R.string.keyboard_model_not_ready),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onOpenApp) {
                    Text(
                        stringResource(R.string.keyboard_open_app_to_download),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            ModelBannerState.InitFailed -> {
                Text(
                    initFailedMessage ?: stringResource(R.string.keyboard_model_not_ready),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun UnifiedVoicePanel(
    phase: VoicePanelPhase,
    recordingVisual: Float,
    modelLoadProgress: Float?,
    modelWarming: Boolean,
    waveformBuffer: WaveformBuffer,
    sampleCountFlow: StateFlow<Long>,
    onStart: () -> Unit,
) {
    val sampleCount by sampleCountFlow.collectAsStateWithLifecycle()
    val processingVisual by animateFloatAsState(
        targetValue = if (phase.processingPhase() != null) 1f else 0f,
        animationSpec = tween(VoiceTransitionMs, easing = FastOutSlowInEasing),
        label = "processingVisual",
    )
    val loadingVisual by animateFloatAsState(
        targetValue = if (phase == VoicePanelPhase.LoadingModel) 1f else 0f,
        animationSpec = tween(VoiceTransitionMs, easing = FastOutSlowInEasing),
        label = "loadingVisual",
    )
    val idleVisual =
        (1f - recordingVisual).coerceIn(0f, 1f) *
            (1f - processingVisual).coerceIn(0f, 1f) *
            (1f - loadingVisual).coerceIn(0f, 1f)
    val waveformVisual = recordingVisual * (1f - processingVisual).coerceIn(0f, 1f) * (1f - loadingVisual).coerceIn(0f, 1f)

    Box(modifier = Modifier.fillMaxSize()) {
        if (recordingVisual > 0.01f) {
            KeyboardRecordingGlow(
                modifier = Modifier.matchParentSize(),
                strength = recordingVisual,
            )
        }

        if (waveformVisual > 0.01f) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = waveformVisual },
                contentAlignment = Alignment.Center,
            ) {
                AudioWaveform(
                    waveformBuffer = waveformBuffer,
                    sampleCount = sampleCount,
                    isActive = phase == VoicePanelPhase.Recording,
                    // KBD-7: recording is the happy path, not an error — drive the waveform from the
                    // brand "recording/live" accent so it is cohesive with the rest of the app
                    // instead of the off-brand Material error red.
                    color = MaterialTheme.colorScheme.chirpAccents.recordingLive,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    minBarHeight = 4.dp,
                    maxBarHeight = 40.dp,
                    showIdlePlaceholder = false,
                )
            }
        }

        if (processingVisual > 0.01f) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = processingVisual },
                contentAlignment = Alignment.Center,
            ) {
                when (val processingPhase = phase.processingPhase()) {
                    null -> Unit
                    else -> VoiceProcessingContent(processingPhase)
                }
            }
        }

        if (loadingVisual > 0.01f) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = loadingVisual },
                contentAlignment = Alignment.Center,
            ) {
                ModelLoadingContent(progress = modelLoadProgress)
            }
        }

        if (phase == VoicePanelPhase.Idle && idleVisual > 0.01f) {
            // KBD-6: a calm always-on aura behind the resting mic so the hero affordance reads as
            // present/premium even at rest, not only while recording.
            KeyboardIdleMicGlow(
                modifier = Modifier.matchParentSize(),
                strength = idleVisual,
            )
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = idleVisual },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // KBD-2/KBD-3: while the model warms into RAM, the mic breathes (brandedPulse)
                    // so the wait reads as "getting ready" rather than a dead tap. The mic stays
                    // tappable — onStart drives the warm forward and owns the delay visually — so a
                    // tap during warmup never no-ops (we mask the wait, never hard-disable it).
                    ChirpVoiceTriggerButton(
                        onClick = onStart,
                        contentDescription = stringResource(R.string.keyboard_desc_start_recording),
                        modifier = if (modelWarming) Modifier.brandedPulse() else Modifier,
                    )
                    Text(
                        stringResource(R.string.keyboard_tap_to_speak),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingActionsRow(
    visibility: Float,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recordingStopPulse")
    // Keep the pulse as State (no `by`) and compute the scale inside graphicsLayer so the infinite
    // transition only invalidates the draw/layer phase rather than recomposing the row (CMP-10).
    val stopPulse = infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOutQuad), RepeatMode.Reverse),
        label = "stopPulse",
    )
    val touchEnabled = visibility > 0.5f

    Box(
        // Animate the row's reserved height with the same crossfade value so the centered mic/
        // waveform area glides instead of snapping 64dp on each dictation start/stop (UI-1).
        modifier =
            modifier
                .height(RecordingActionsHeight * visibility.coerceIn(0f, 1f))
                .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = visibility
                        if (visibility <= 0.01f) {
                            clip = true
                        }
                    },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onCancel,
                enabled = touchEnabled,
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, ChirpShapes.Small)
                        .size(42.dp),
            ) {
                Icon(Icons.Filled.Close, "Cancel recording", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            FloatingActionButton(
                onClick = { if (touchEnabled) onStop() },
                modifier =
                    Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            val stopScale = 1f + (stopPulse.value - 1f) * visibility
                            scaleX = stopScale
                            scaleY = stopScale
                        },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
            ) {
                Icon(Icons.Filled.Stop, stringResource(R.string.keyboard_desc_stop_recording), Modifier.size(28.dp))
            }

            IconButton(
                onClick = onRestart,
                enabled = touchEnabled,
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, ChirpShapes.Small)
                        .size(42.dp),
            ) {
                Icon(Icons.Filled.Refresh, "Restart recording", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VoiceProcessingContent(phase: ProcessingPhase) {
    val message =
        when (phase) {
            ProcessingPhase.Transcribing -> stringResource(R.string.keyboard_transcribing)
            ProcessingPhase.Polishing -> stringResource(R.string.keyboard_polishing)
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThinkingDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KeyboardControls(
    onBackspace: () -> Unit,
    onBackspaceWord: () -> Unit,
    onSpace: () -> Unit,
    onMoveCursor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackspaceKey(
            onDeleteCharacter = onBackspace,
            onDeleteWord = onBackspaceWord,
            modifier =
                Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        ChirpShapes.Small,
                    ).size(48.dp),
        )

        SpaceBarKey(
            onSpace = onSpace,
            onMoveCursor = onMoveCursor,
            modifier = Modifier.weight(1f).height(48.dp),
        )
    }
}

@Composable
private fun BackspaceKey(
    onDeleteCharacter: () -> Unit,
    onDeleteWord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deleteLabel = stringResource(R.string.keyboard_desc_delete)

    Box(
        modifier =
            modifier
                .clip(ChirpShapes.Small)
                .minimumInteractiveComponentSize()
                .semantics {
                    role = Role.Button
                    onClick(label = deleteLabel) {
                        onDeleteCharacter()
                        true
                    }
                }.pointerInput(onDeleteCharacter, onDeleteWord) {
                    coroutineScope {
                        while (true) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var isPressed = true
                                val pressStart = SystemClock.uptimeMillis()
                                var wordMode = false
                                var repeatCount = 0

                                HapticFeedback.onBackspace(context)
                                onDeleteCharacter()

                                val repeatJob =
                                    this@coroutineScope.launch {
                                        delay(BackspaceInitialRepeatDelayMs)
                                        while (isPressed) {
                                            val holdDuration = SystemClock.uptimeMillis() - pressStart
                                            if (!wordMode && shouldEnterBackspaceWordMode(holdDuration)) {
                                                wordMode = true
                                                HapticFeedback.onBackspaceWordMode(context)
                                            }

                                            if (wordMode) {
                                                onDeleteWord()
                                            } else {
                                                onDeleteCharacter()
                                            }

                                            repeatCount++
                                            if (repeatCount % 4 == 0) {
                                                HapticFeedback.onBackspace(context)
                                            }

                                            delay(backspaceRepeatIntervalMs(holdDuration, wordMode))
                                        }
                                    }
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed ||
                                            !isPointerInsideKey(
                                                position = change.position,
                                                width = size.width,
                                                height = size.height,
                                            )
                                        ) {
                                            break
                                        }
                                    }
                                } finally {
                                    isPressed = false
                                    repeatJob.cancel()
                                }
                            }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Backspace,
            contentDescription = deleteLabel,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SpaceBarKey(
    onSpace: () -> Unit,
    onMoveCursor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cursorStepPx = with(density) { SpaceCursorDragStep.toPx() }
    val cursorDragStartThresholdPx = LocalViewConfiguration.current.touchSlop
    val spaceLabel = stringResource(R.string.keyboard_desc_space)

    Box(
        modifier =
            modifier
                .clip(ChirpShapes.Small)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .semantics {
                    role = Role.Button
                    onClick(label = spaceLabel) {
                        onSpace()
                        true
                    }
                }.pointerInput(onSpace, onMoveCursor, cursorStepPx, cursorDragStartThresholdPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var cursorMode = false
                        var cursorAccumulated = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!cursorMode) {
                                    onSpace()
                                }
                                break
                            }

                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!cursorMode && shouldStartSpaceCursorDrag(dx, dy, cursorDragStartThresholdPx)) {
                                cursorMode = true
                            }

                            if (cursorMode) {
                                val dragDelta = change.position.x - change.previousPosition.x
                                change.consume()
                                cursorAccumulated += dragDelta
                                while (cursorAccumulated >= cursorStepPx) {
                                    onMoveCursor(1)
                                    HapticFeedback.onCursorStep(context)
                                    cursorAccumulated -= cursorStepPx
                                }
                                while (cursorAccumulated <= -cursorStepPx) {
                                    onMoveCursor(-1)
                                    HapticFeedback.onCursorStep(context)
                                    cursorAccumulated += cursorStepPx
                                }
                            }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.SpaceBar,
                contentDescription = spaceLabel,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                spaceLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun KeyboardRecordingGlow(
    modifier: Modifier = Modifier,
    strength: Float = 1f,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "keyboardRecordingGlow")
    // Keep the animated alpha as State (no `by`) so the infinite transition only invalidates the
    // draw phase below — never re-runs composition while recording (CMP-9).
    val pulseAlpha = infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.32f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "keyboardGlowAlpha",
    )
    // KBD-7: the active-recording glow uses the brand "recording/live" accent (not Material error
    // red) so "we are capturing" reads as live + premium and matches the waveform, the recognition
    // dialog and the record screen rather than signalling danger.
    val accents = MaterialTheme.colorScheme.chirpAccents
    val liveContainer = accents.recordingLiveContainer
    val live = accents.recordingLive

    Canvas(modifier = modifier) {
        val glowAlpha = pulseAlpha.value * strength
        val cornerPx = ChirpShapes.KeyboardPanelCornerRadius.toPx()
        val cornerRadius = CornerRadius(cornerPx, cornerPx)
        drawRoundRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            live.copy(alpha = glowAlpha),
                            liveContainer.copy(alpha = glowAlpha * 0.45f),
                            Color.Transparent,
                        ),
                    center = Offset(size.width / 2f, size.height * 0.72f),
                    radius = size.maxDimension * 0.85f,
                ),
            cornerRadius = cornerRadius,
        )
        drawRoundRect(
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color.Transparent,
                            liveContainer.copy(alpha = glowAlpha * 0.25f),
                            live.copy(alpha = glowAlpha * 0.4f),
                        ),
                    startY = size.height * 0.35f,
                    endY = size.height,
                ),
            cornerRadius = cornerRadius,
        )
    }
}

/**
 * A calm, always-on aura behind the resting mic (KBD-6).
 *
 * A soft radial brand-primary glow at low alpha with a very slow breathing pulse, so the idle hero
 * mic reads as present and premium rather than an inert flat square. Deliberately understated and
 * tinted with the brand `primary` family (distinct from the warmer recording-live glow), and
 * draw-phase-only like [KeyboardRecordingGlow] so the breathing never recomposes the panel.
 */
@Composable
private fun KeyboardIdleMicGlow(
    modifier: Modifier = Modifier,
    strength: Float = 1f,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "keyboardIdleMicGlow")
    val breath = infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.16f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(2600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "keyboardIdleGlowAlpha",
    )
    val glow = MaterialTheme.colorScheme.primary
    val glowContainer = MaterialTheme.colorScheme.primaryContainer

    Canvas(modifier = modifier) {
        val glowAlpha = breath.value * strength
        val cornerPx = ChirpShapes.KeyboardPanelCornerRadius.toPx()
        val cornerRadius = CornerRadius(cornerPx, cornerPx)
        drawRoundRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            glow.copy(alpha = glowAlpha),
                            glowContainer.copy(alpha = glowAlpha * 0.5f),
                            Color.Transparent,
                        ),
                    // Center on the mic FAB (slightly above panel center where the mic sits).
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = size.maxDimension * 0.55f,
                ),
            cornerRadius = cornerRadius,
        )
    }
}

@Composable
private fun ModelLoadingContent(progress: Float?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(
                stringResource(R.string.keyboard_downloading_model_progress, (progress * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(
                stringResource(R.string.keyboard_loading_speech_model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onTap: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        FilledTonalButton(
            onClick = onTap,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.keyboard_retry))
        }
    }
}

@Composable
private fun LlmErrorContent(
    message: String,
    onDismiss: () -> Unit = {},
) {
    LaunchedEffect(message) {
        delay(3000)
        onDismiss()
    }
    Row(
        modifier =
            Modifier
                .padding(horizontal = 24.dp)
                .minimumInteractiveComponentSize()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.errorContainer)
                .semantics(mergeDescendants = true) {}
                .clickable { onDismiss() }
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
