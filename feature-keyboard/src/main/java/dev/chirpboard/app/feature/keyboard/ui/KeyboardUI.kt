package dev.chirpboard.app.feature.keyboard.ui

import android.content.res.Configuration
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModeListItem
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.recording.WaveformBuffer
import dev.chirpboard.app.core.ui.components.ChirpLlmToggle
import dev.chirpboard.app.core.ui.components.InputDeviceChip
import dev.chirpboard.app.core.ui.components.InputDeviceListContent
import dev.chirpboard.app.core.ui.components.InputDevicePickerUiState
import dev.chirpboard.app.core.ui.components.ChirpVoiceTriggerButton
import dev.chirpboard.app.core.ui.components.ThinkingDots
import dev.chirpboard.app.core.ui.components.brandedPulse
import dev.chirpboard.app.core.ui.components.recording.AudioWaveform
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.core.ui.theme.chirpAccents
import dev.chirpboard.app.feature.keyboard.R
import dev.chirpboard.app.feature.keyboard.haptic.HapticFeedback
import dev.chirpboard.app.feature.keyboard.service.KeyboardImeAction
import dev.chirpboard.app.feature.keyboard.service.KeyboardImeActionKind
import dev.chirpboard.app.feature.keyboard.session.KeyboardOverlayError
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
private const val LlmErrorAutoDismissMs = 3000
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
    data class ErrorOverlay(val message: String, val showOpenApp: Boolean = false) : KeyboardPanelContent

    data class LlmError(val message: String) : KeyboardPanelContent

    data class RecognitionError(val message: String) : KeyboardPanelContent

    data object SensitiveNotice : KeyboardPanelContent

    data object Panel : KeyboardPanelContent
}

/** Stable discriminator so the crossfade only runs on error<->panel kind changes, not on the */
/** panel's own internal Idle/Recording/Loading sub-transitions. */
internal enum class KeyboardPanelContentKind {
    ErrorOverlay,
    LlmError,
    RecognitionError,
    SensitiveNotice,
    Panel,
}

internal fun KeyboardPanelContent.kind(): KeyboardPanelContentKind =
    when (this) {
        is KeyboardPanelContent.ErrorOverlay -> KeyboardPanelContentKind.ErrorOverlay
        is KeyboardPanelContent.LlmError -> KeyboardPanelContentKind.LlmError
        is KeyboardPanelContent.RecognitionError -> KeyboardPanelContentKind.RecognitionError
        KeyboardPanelContent.SensitiveNotice -> KeyboardPanelContentKind.SensitiveNotice
        KeyboardPanelContent.Panel -> KeyboardPanelContentKind.Panel
    }

internal fun resolveKeyboardPanelContent(
    errorOverlay: KeyboardOverlayError?,
    voicePanel: VoicePanelPhase,
    errorMessage: String?,
    llmErrorMessage: String?,
    sensitiveInputNotice: Boolean = false,
): KeyboardPanelContent =
    when {
        errorOverlay != null ->
            KeyboardPanelContent.ErrorOverlay(errorOverlay.message, errorOverlay.showOpenApp)
        // IME-4: password/blocked fields show a calm "dictation off" notice in place of the mic —
        // never an error panel, and never a Retry that resurrects dictation controls. The notice
        // outranks phase-derived errors: dictation cannot start in a sensitive field, so any such
        // error is leftover from a previous field and its Retry would be a dead control here.
        sensitiveInputNotice -> KeyboardPanelContent.SensitiveNotice
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

@Composable
private fun defaultKeyboardModeOptions(): List<ProcessingModeListItem> =
    keyboardModeOptions().map { option ->
        // I18N-08: resolve the names from the keyboard_mode_* resources instead of duplicating
        // them as Kotlin literals.
        ProcessingModeListItem(id = option.id, name = stringResource(option.labelRes))
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
    // IME-1: the editor-derived action key (Done/Search/Send/Next/Go, falling back to Enter).
    imeAction: KeyboardImeAction = KeyboardImeAction.Enter,
    onImeAction: () -> Unit = {},
    onOpenApp: () -> Unit = {},
    onDismissError: () -> Unit = {},
    // DECISIONS (Color/brand): brand lavender is the default; the host service collects the user's
    // "Use system colors (Material You)" preference and passes it through so the keyboard matches
    // the app's chosen palette.
    dynamicColor: Boolean = false,
    // PRF-3: ambient (infinite) animations compose only while the IME window is actually shown.
    windowShown: Boolean = true,
    // AUDIODEV: compact input-device picker (chip near the AI toggle). Selection applies to the
    // NEXT capture start; the host service wires the persisted preference + live device list.
    inputDevicePicker: InputDevicePickerUiState = InputDevicePickerUiState(),
    onSelectInputDeviceAutomatic: () -> Unit = {},
    onSelectInputDevice: (AudioInputDeviceSummary) -> Unit = {},
    onRequestBluetoothNames: (() -> Unit)? = null,
) {
    KeyboardTheme(dynamicColor = dynamicColor) {
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

        // IME-13: a phone in landscape has ~360-400dp of window height; the portrait panel would
        // leave no app content visible, so a compact layout takes over. A11Y-5: the max bound
        // additionally grows with the font scale so large-font labels never clip against a fixed
        // ceiling (half-rate growth keeps the keyboard from swallowing the whole screen at 2.0).
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val fontScale = density.fontScale.coerceIn(1f, 2f)
        val fontScaleGrowth = 1f + (fontScale - 1f) * 0.5f
        val minPanelHeight = if (isLandscape) 200.dp else 284.dp
        val maxPanelHeight = (if (isLandscape) 232.dp else 320.dp) * fontScaleGrowth

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minPanelHeight + bottomInset, max = maxPanelHeight + bottomInset)
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
                    inputDevicePicker = inputDevicePicker,
                    onSelectInputDeviceAutomatic = onSelectInputDeviceAutomatic,
                    onSelectInputDevice = onSelectInputDevice,
                    onRequestBluetoothNames = onRequestBluetoothNames,
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
                        sensitiveInputNotice = uiState.sensitiveInputNotice,
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
                                if (content.showOpenApp) {
                                    // ERR-8: the IME cannot request RECORD_AUDIO itself; route to
                                    // the app instead of a Retry that can never succeed.
                                    PermissionErrorContent(content.message, onOpenApp)
                                } else {
                                    ErrorContent(content.message, onDismissError)
                                }

                            is KeyboardPanelContent.LlmError ->
                                LlmErrorContent(content.message, onDismissError)

                            is KeyboardPanelContent.RecognitionError ->
                                ErrorContent(content.message, onMicTap)

                            KeyboardPanelContent.SensitiveNotice ->
                                SensitiveFieldNotice()

                            KeyboardPanelContent.Panel ->
                                UnifiedVoicePanel(
                                    phase = voicePhase,
                                    recordingVisual = recordingVisual,
                                    modelLoadProgress = uiState.modelLoadProgress,
                                    modelWarming = modelWarming,
                                    waveformBuffer = waveformBuffer,
                                    sampleCountFlow = sampleCountFlow,
                                    windowShown = windowShown,
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
                        imeAction = imeAction,
                        onImeAction = onImeAction,
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
    inputDevicePicker: InputDevicePickerUiState = InputDevicePickerUiState(),
    onSelectInputDeviceAutomatic: () -> Unit = {},
    onSelectInputDevice: (AudioInputDeviceSummary) -> Unit = {},
    onRequestBluetoothNames: (() -> Unit)? = null,
) {
    val statusLabelRes = uiState.statusLabelRes()

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            // A11Y-5: a minimum (not fixed) height so labelMedium at font scale 2.0 is not
            // vertically clipped.
            modifier = Modifier.heightIn(min = 20.dp).weight(1f, fill = false),
            contentAlignment = Alignment.CenterStart,
        ) {
            Crossfade(
                targetState = statusLabelRes ?: 0,
                animationSpec = tween(VoiceTransitionMs, easing = FastOutSlowInEasing),
                label = "keyboardStatusLabel",
            ) { labelRes ->
                if (labelRes != 0) {
                    val activeDeviceName = inputDevicePicker.activeDevice?.summary?.productName
                    val statusText =
                        if (labelRes == R.string.keyboard_status_no_audio && activeDeviceName != null) {
                            // AUD-02 + device picker: name the silent device and suggest
                            // switching, mirroring the recorder's named silence hint.
                            stringResource(R.string.keyboard_status_no_audio_device, activeDeviceName)
                        } else {
                            stringResource(labelRes)
                        }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        // AUD-02: the "no audio detected" hint is a warning, not a phase —
                        // tint it with the error role so it stands out from the calm labels.
                        color =
                            if (labelRes == R.string.keyboard_status_no_audio) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        // A11Y-1: the status label is the keyboard's only textual phase feedback
                        // (Recording/Transcribing/Polishing) — announce its changes politely so
                        // TalkBack users hear dictation state transitions.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyboardInputDeviceMenu(
                pickerState = inputDevicePicker,
                // MIC-004: trust the service's origin-scoped derivation (KEYBOARD owns the
                // active recording, covering Starting/Stopping too) instead of recomputing
                // from the voice phase, which missed the stop window and could not be
                // origin-checked from here.
                sessionLive = inputDevicePicker.sessionLive,
                onSelectAutomatic = onSelectInputDeviceAutomatic,
                onSelectDevice = onSelectInputDevice,
                onRequestBluetoothNames = onRequestBluetoothNames,
            )

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
}

/**
 * AUDIODEV: unobtrusive input-device chip beside the AI toggle. Opens the shared device
 * list as a [DropdownMenu] (popups work inside the IME window where a modal sheet would
 * not). Selection applies to the NEXT dictation; a live session shows the note inline.
 * The keyboard cannot request runtime permissions, so the Bluetooth-names affordance
 * routes to the app via [onRequestBluetoothNames] (Open-app pattern, like ERR-8).
 */
@Composable
private fun KeyboardInputDeviceMenu(
    pickerState: InputDevicePickerUiState,
    sessionLive: Boolean,
    onSelectAutomatic: () -> Unit,
    onSelectDevice: (AudioInputDeviceSummary) -> Unit,
    onRequestBluetoothNames: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    val state = pickerState.copy(sessionLive = sessionLive)
    // The compact surface has no room for the full "Using X — Y isn't connected" notice,
    // so a preferred-absent fallback tints the chip instead; the open menu names the
    // missing device on its "Not connected" row.
    val fallbackActive = sessionLive && state.activeDevice?.fallbackFromPreferredName != null

    Box {
        InputDeviceChip(
            state = state,
            onClick = { expanded = true },
            modifier = Modifier.widthIn(max = KeyboardDeviceChipMaxWidth),
            containerColor =
                if (fallbackActive) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            contentColor =
                if (fallbackActive) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            InputDeviceListContent(
                state = state,
                onSelectAutomatic = {
                    onSelectAutomatic()
                    expanded = false
                },
                onSelectDevice = { device ->
                    onSelectDevice(device)
                    expanded = false
                },
                onRequestBluetoothNames =
                    onRequestBluetoothNames?.let { request ->
                        {
                            request()
                            expanded = false
                        }
                    },
                modifier = Modifier.widthIn(min = 220.dp, max = KeyboardDeviceMenuMaxWidth),
            )
        }
    }
}

private val KeyboardDeviceChipMaxWidth = 148.dp
private val KeyboardDeviceMenuMaxWidth = 300.dp

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
    windowShown: Boolean,
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
            // present/premium even at rest, not only while recording. PRF-3: gated on the window
            // actually being shown so the infinite transition can never animate behind a hidden
            // IME window even if the pausable-frame-clock wiring regresses.
            if (windowShown) {
                KeyboardIdleMicGlow(
                    modifier = Modifier.matchParentSize(),
                    strength = idleVisual,
                )
            }
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
                // A11Y-6: 48dp interactive target (was 42dp), matching the backspace/space keys.
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, ChirpShapes.Small)
                        .size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    stringResource(R.string.keyboard_desc_cancel_recording),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                // A11Y-6: 48dp interactive target (was 42dp), matching the backspace/space keys.
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, ChirpShapes.Small)
                        .size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    stringResource(R.string.keyboard_desc_restart_recording),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // A11Y-1: announce the Transcribing/Polishing processing phases.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The neutral center-panel notice for password/blocked fields (IME-4): dictation is off, but the
 * surrounding typing aids (backspace, space, cursor drag, action key) remain fully usable. No
 * Retry — there is nothing to retry on a secure field.
 */
@Composable
private fun SensitiveFieldNotice() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .padding(horizontal = 24.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.keyboard_sensitive_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KeyboardControls(
    onBackspace: () -> Unit,
    onBackspaceWord: () -> Unit,
    onSpace: () -> Unit,
    onMoveCursor: (Int) -> Unit,
    imeAction: KeyboardImeAction,
    onImeAction: () -> Unit,
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

        // IME-1: the editor-action key — Done/Search/Send/Next/Go per imeOptions, Enter otherwise.
        ImeActionKey(
            action = imeAction,
            onImeAction = onImeAction,
            modifier =
                Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        ChirpShapes.Small,
                    ).size(48.dp),
        )
    }
}

@StringRes
private fun KeyboardImeActionKind.labelRes(): Int =
    when (this) {
        KeyboardImeActionKind.ENTER -> R.string.keyboard_action_enter
        KeyboardImeActionKind.DONE -> R.string.keyboard_action_done
        KeyboardImeActionKind.SEARCH -> R.string.keyboard_action_search
        KeyboardImeActionKind.SEND -> R.string.keyboard_action_send
        KeyboardImeActionKind.NEXT -> R.string.keyboard_action_next
        KeyboardImeActionKind.GO -> R.string.keyboard_action_go
        KeyboardImeActionKind.PREVIOUS -> R.string.keyboard_action_previous
    }

@Composable
private fun ImeActionKey(
    action: KeyboardImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val label = stringResource(action.kind.labelRes())
    val icon =
        when (action.kind) {
            KeyboardImeActionKind.ENTER -> Icons.AutoMirrored.Filled.KeyboardReturn
            KeyboardImeActionKind.DONE -> Icons.Filled.Check
            KeyboardImeActionKind.SEARCH -> Icons.Filled.Search
            KeyboardImeActionKind.SEND -> Icons.AutoMirrored.Filled.Send
            KeyboardImeActionKind.NEXT, KeyboardImeActionKind.GO -> Icons.AutoMirrored.Filled.ArrowForward
            KeyboardImeActionKind.PREVIOUS -> Icons.AutoMirrored.Filled.ArrowBack
        }

    Box(
        modifier =
            modifier
                .clip(ChirpShapes.Small)
                .minimumInteractiveComponentSize()
                .clickable(onClickLabel = label, role = Role.Button) {
                    HapticFeedback.onKeyTap(context)
                    onImeAction()
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
    val deleteWordLabel = stringResource(R.string.keyboard_action_delete_word)

    Box(
        modifier =
            modifier
                .clip(ChirpShapes.Small)
                .minimumInteractiveComponentSize()
                // A11Y-4: merge so TalkBack announces the key by its icon label ("Delete") instead
                // of an anonymous "Button" plus a stray child node. A11Y-7: hold-to-delete-word is
                // unreachable through TalkBack's double-tap, so expose it as a custom action.
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    onClick(label = deleteLabel) {
                        onDeleteCharacter()
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(deleteWordLabel) {
                                onDeleteWord()
                                true
                            },
                        )
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
    val cursorLeftLabel = stringResource(R.string.keyboard_action_cursor_left)
    val cursorRightLabel = stringResource(R.string.keyboard_action_cursor_right)

    Box(
        modifier =
            modifier
                .clip(ChirpShapes.Small)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                // A11Y-4: merge so the key announces as "Space" instead of an unnamed button with
                // detached children. A11Y-7: the horizontal cursor drag has no TalkBack gesture
                // equivalent and this keyboard has no arrow keys, so expose cursor movement as
                // custom actions.
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    onClick(label = spaceLabel) {
                        onSpace()
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(cursorLeftLabel) {
                                onMoveCursor(-1)
                                true
                            },
                            CustomAccessibilityAction(cursorRightLabel) {
                                onMoveCursor(1)
                                true
                            },
                        )
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
                // A11Y-4: the merged key already reads "Space" from the Text below; a description
                // here would double-announce it.
                contentDescription = null,
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
    // PRF-4: the gradients are built once per size/palette with RELATIVE alpha stops and the
    // per-frame pulse is applied via drawRoundRect's alpha parameter — zero allocations per frame.
    val brushCache = remember(live, liveContainer) { SizedBrushCache<Pair<Brush, Brush>>() }

    Canvas(modifier = modifier) {
        val glowAlpha = (pulseAlpha.value * strength).coerceIn(0f, 1f)
        val cornerPx = ChirpShapes.KeyboardPanelCornerRadius.toPx()
        val cornerRadius = CornerRadius(cornerPx, cornerPx)
        val (radialBrush, verticalBrush) =
            brushCache.brushesFor(size) {
                Pair(
                    Brush.radialGradient(
                        colors = listOf(live, liveContainer.copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.72f),
                        radius = size.maxDimension * 0.85f,
                    ),
                    Brush.verticalGradient(
                        // 0.625 * the layer alpha factor of 0.4 == the original 0.25 mid stop.
                        colors = listOf(Color.Transparent, liveContainer.copy(alpha = 0.625f), live),
                        startY = size.height * 0.35f,
                        endY = size.height,
                    ),
                )
            }
        drawRoundRect(brush = radialBrush, cornerRadius = cornerRadius, alpha = glowAlpha)
        drawRoundRect(brush = verticalBrush, cornerRadius = cornerRadius, alpha = glowAlpha * 0.4f)
    }
}

/**
 * Per-size brush holder for the keyboard glows (PRF-4): rebuilt only when the canvas size (or the
 * remember key palette) changes, so the per-frame draw never allocates gradients or color lists.
 */
private class SizedBrushCache<T : Any> {
    private var cachedSize: Size = Size.Unspecified
    private var cached: T? = null

    fun brushesFor(
        size: Size,
        build: () -> T,
    ): T {
        val existing = cached
        if (existing != null && cachedSize == size) {
            return existing
        }
        val built = build()
        cached = built
        cachedSize = size
        return built
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
    // PRF-4: one gradient per size/palette; the breathing is applied via the draw alpha parameter.
    val brushCache = remember(glow, glowContainer) { SizedBrushCache<Brush>() }

    Canvas(modifier = modifier) {
        val glowAlpha = (breath.value * strength).coerceIn(0f, 1f)
        val cornerPx = ChirpShapes.KeyboardPanelCornerRadius.toPx()
        val cornerRadius = CornerRadius(cornerPx, cornerPx)
        val radialBrush =
            brushCache.brushesFor(size) {
                Brush.radialGradient(
                    colors = listOf(glow, glowContainer.copy(alpha = 0.5f), Color.Transparent),
                    // Center on the mic FAB (slightly above panel center where the mic sits).
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = size.maxDimension * 0.55f,
                )
            }
        drawRoundRect(brush = radialBrush, cornerRadius = cornerRadius, alpha = glowAlpha)
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
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            // A11Y-1: announce recognition/session errors when they appear.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
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

/**
 * Mic-permission error panel (ERR-8): an IME cannot request runtime permissions, so the only
 * useful affordance is opening the app to grant microphone access — never a dead-end Retry.
 */
@Composable
private fun PermissionErrorContent(
    message: String,
    onOpenApp: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        FilledTonalButton(onClick = onOpenApp) {
            Text(stringResource(R.string.keyboard_open_app_for_mic))
        }
    }
}

@Composable
private fun LlmErrorContent(
    message: String,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    // A11Y: honor the system's recommended timeout so TalkBack/magnification/switch users get
    // long enough to perceive the error before it auto-dismisses (still tappable to dismiss).
    val dismissDelayMs =
        remember(context) {
            val accessibilityManager =
                context.getSystemService(AccessibilityManager::class.java)
            (
                accessibilityManager?.getRecommendedTimeoutMillis(
                    LlmErrorAutoDismissMs,
                    AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
                ) ?: LlmErrorAutoDismissMs
            ).toLong()
        }
    LaunchedEffect(message) {
        delay(dismissDelayMs)
        onDismiss()
    }
    Row(
        modifier =
            Modifier
                .padding(horizontal = 24.dp)
                .minimumInteractiveComponentSize()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.errorContainer)
                // A11Y-1: announce AI-enhancement failures when the banner appears.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .clickable { onDismiss() }
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
