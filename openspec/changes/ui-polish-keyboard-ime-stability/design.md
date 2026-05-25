# Design: UI polish — keyboard IME stability and voice-input alignment

## Context

`ChirpKeyboardService` hosts a custom `Recomposer` and `ComposeView` for the IME. Today:

```kotlin
// onCreateInputView
composeView?.disposeComposition()
val view = ComposeView(this).apply { setContent { KeyboardUI(...) } }

// onFinishInputView
composeView?.disposeComposition()
composeView = null
```

Every field switch or keyboard hide triggers full composition disposal. `KeyboardUI` wraps all state bodies in `AnimatedContent(targetState = state)`, including `IdleContent` and `RecordingContent` that each embed their own `ModeSelector` + `LlmToggle` row. Scroll state inside `ModeSelector` is lost when crossing Idle ↔ Recording.

`VoiceRecognitionDialog` (app module) already uses:
- `RecordingTimer` + monospace display during active capture
- `ThinkingDots` while stopping/processing
- `Icons.Filled.Stop` on the stop affordance
- `Icons.Filled.AutoAwesome` on the LLM chip with mode-aware label

`KeyboardUI` diverges: Check icon on stop FAB, `CircularProgressIndicator` for transcribing/polishing, static "LLM" + Check chip, no timer.

`ModelNotReadyContent` shows icon + two text lines with no tap target.

`RecordingWidgetProvider` hardcodes `"Tap to record"`, `"Starting..."`, `"Saving..."`, `"Error"`, hex tints (`0xFFE53935`, `0xFF9E9E9E`), and layout XML uses literal `#FFFFFF` / `#CC1C1C1E`.

`ChirpMotion` defines `studioContentCrossfade` for Processing Studio phase morphs; keyboard processing states use ad hoc 200 ms fades.

## Goals / Non-Goals

**Goals:**

- Preserve Compose composition across IME show/hide cycles until service destroy.
- Unify keyboard voice-input visuals with dialog/recording surfaces using existing `core-ui` components.
- Resolve Stop vs Check semantics in favor of platform-standard Stop.
- Keep `ModeSelector` scroll offset when toggling Idle ↔ Recording.
- Provide actionable ModelNotReady CTA.
- Localize widget status strings; centralize widget colors; use branded small icon for widget error notifications.
- Add keyboard-specific `ChirpMotion` crossfade for transcribing ↔ polishing.

**Non-Goals:**

- Full extraction of `VoiceInputPanel` shared composable (optional follow-up).
- Changing `InputMethodService` lifecycle beyond composition caching.
- Dynamic Material You colors on RemoteViews widget.
- Keyboard waveform or haptic behavior changes (covered by existing specs).

## Decisions

### 1. IME ComposeView caching strategy

**Decision:** Cache a single `ComposeView` instance on the service; create once in `onCreateInputView` if null; never call `disposeComposition()` in `onFinishInputView` or at the start of `onCreateInputView`. Call `disposeComposition()` only in `onDestroy` after lifecycle teardown.

**Rationale:** `onCreateInputView` may be invoked multiple times per service lifetime. Disposing on hide forces full recomposition, resets scroll/animation state, and increases jank on field switches. Android IME samples recommend retaining the input view where possible.

**Implementation sketch:**

```kotlin
override fun onCreateInputView(): View {
    return composeView ?: ComposeView(this).also { view ->
        view.compositionContext = recomposer
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setContent { /* KeyboardUI with state collectors */ }
        composeView = view
    }
}

override fun onFinishInputView(finishingInput: Boolean) {
    super.onFinishInputView(finishingInput)
    // Do NOT dispose composition or null composeView
    if (_state.value is KeyboardState.Recording) { finalizeActiveRecording(...) }
}
```

**Alternative considered:** Dispose only when `finishingInput == true` — rejected; partial hides still cause scroll reset and flicker.

**Alternative considered:** `ViewStub` lazy inflation — unnecessary; single cached view is simpler.

### 2. Voice input pattern alignment (extract vs inline)

**Decision:** Reuse existing `core-ui` primitives directly in `KeyboardUI` (`RecordingTimer`, `ThinkingDots`, `AudioWaveform`, `RecordingGlowBackground`). Update `LlmToggle` to match dialog chip semantics (`AutoAwesome` leading icon, mode-aware label when enabled). Do **not** introduce a new `VoiceInputPanel` composable in this change.

**Rationale:** `RecordingTimer` and `ThinkingDots` already live in `core-ui`. Dialog and keyboard layouts differ (keyboard has backspace/space/mode chips; dialog has transcript area). Forcing a shared panel adds abstraction without reducing duplication meaningfully.

**Keyboard recording layout changes:**

| Element | Before | After |
|---------|--------|-------|
| Elapsed time | None | `RecordingTimer` above waveform (map `KeyboardState.Recording` + sample timing via existing flows or duration derived from `sampleCountFlow`) |
| Stop FAB icon | `Icons.Filled.Check` | `Icons.Filled.Stop` |
| Stop content description | `keyboard_desc_stop_recording` | unchanged string key |
| Processing indicator | `CircularProgressIndicator` + text | `ThinkingDots` + crossfaded status label |
| LLM chip | "LLM" + Check when selected | `AutoAwesome` + mode name when enabled (mirror dialog label pattern) |

**Alternative considered:** Extract `VoiceInputPanel` to `core-ui` — deferred; revisit if a third surface needs the same layout.

### 3. Stop vs Check icon semantics

**Decision:** Use **Stop** (`Icons.Filled.Stop`) on the keyboard stop FAB during `KeyboardState.Recording`.

**Rationale:** Check implies confirmation/completion; Stop matches `VoiceRecognitionDialog`, Material recording patterns, and TalkBack user expectations. `keyboard_desc_stop_recording` already describes stop semantics.

**Alternative considered:** Keep Check as "done speaking" metaphor — rejected for cross-surface inconsistency and a11y confusion.

### 4. Processing phase crossfade (`ChirpMotion`)

**Decision:** Add `keyboardProcessingCrossfade` to `ChirpMotion`:

```kotlin
val keyboardProcessingCrossfade: ContentTransform =
    fadeIn(tween(durationMillis = 250, easing = FastOutSlowInEasing)) togetherWith
        fadeOut(tween(durationMillis = 200, easing = FastOutSlowInEasing))
```

Use nested `AnimatedContent` for `Transcribing` vs `Polishing` inside a shared processing container, or treat both as sub-states of a sealed `ProcessingPhase` target. Prefer single processing composable:

```kotlin
AnimatedContent(
    targetState = processingPhase, // Transcribing | Polishing
    transitionSpec = { ChirpMotion.keyboardProcessingCrossfade },
) { phase -> KeyboardProcessingContent(phase) }
```

Keep top-level `AnimatedContent(state)` for major states (Idle, Recording, Processing bucket, Error, etc.) but group `Transcribing` + `Polishing` under one processing branch to avoid double-fade.

**Rationale:** Aligns with `studioContentCrossfade` pattern; 250 ms matches existing status-text animation tier in `ui-animations` spec.

### 5. Hoist ModeSelector outside AnimatedContent

**Decision:** Restructure `KeyboardUI` layout:

```
Column {
  AnimatedContent(state) { /* Idle body OR Recording body — no mode row inside */ }
  if (showModeControls) {
    Row { LlmToggle; ModeSelector }  // pinned below animated region
  }
}
```

`showModeControls = llmEnabled && currentMode != null && state in { Idle, Recording }`.

Single shared `rememberScrollState()` for `ModeSelector` at `KeyboardUI` scope.

**Rationale:** `AnimatedContent` destroys outgoing content, losing `rememberScrollState` in child composables. Hoisting preserves scroll across Idle ↔ Recording transitions.

**Alternative considered:** `saveable` scroll state keyed by mode — rejected; hoisting is simpler and matches user mental model (controls persist at bottom).

### 6. ModelNotReady CTA

**Decision:** Add `FilledTonalButton` labeled `keyboard_open_app_to_download` (or new `keyboard_download_model`) that launches main activity via explicit `Intent` to transcription/download settings or main activity with deep link extra.

Wire `onTap` from service: if model not downloaded, open app; if download in progress, no-op or show progress state.

**Rationale:** Passive text fails users who expect a tap target; matches error/retry patterns elsewhere in keyboard.

**Open detail:** Exact navigation target — default to `MainActivity` (same as foreground notification); optional extra `ACTION_OPEN_TRANSCRIPTION_SETTINGS` if manifest route exists.

### 7. Widget i18n and color tokens

**Decision:**

| Hardcoded value | Replacement |
|-----------------|-------------|
| `"Tap to record"` | `@string/widget_status_idle` |
| `"Starting..."` | `@string/widget_status_starting` |
| `"Saving..."` | `@string/widget_status_saving` |
| `"Error"` | `@string/widget_status_error` |
| Layout `"Chirp"` | `@string/widget_title` |
| `"Toggle recording"` | `@string/widget_desc_toggle_recording` |
| `0xFFE53935` | `@color/widget_accent_recording` |
| `0xFF9E9E9E` | `@color/widget_accent_inactive` |
| `#FFFFFF`, `#B3FFFFFF`, `#CC1C1C1E` | `@color/widget_on_surface`, `@color/widget_on_surface_variant`, `@color/widget_surface` |

Use `context.getColor(R.color.*)` in `RecordingWidgetProvider.setInt(..., "setColorFilter", ...)`.

**Notification icon:** When widget-initiated recording fails and `RecordingService` (or coordinator) posts error notification, use `@drawable/ic_notification` or `@mipmap/ic_launcher` monochrome adaptive asset — add `feature-widget` drawable reference or depend on app `R.drawable.ic_stat_chirp` if already defined. Widget provider itself does not post notifications today; add resource and document for service layer consumption, or update existing error notification builder if wired through widget path.

**Rationale:** RemoteViews cannot use Compose `MaterialTheme`; color resources still enable single-point palette updates and future night qualifiers (`values-night/colors.xml`).

### 8. Icon family for keyboard LLM chip

**Decision:** Use `Icons.Rounded.AutoAwesome` when design-system foundation has standardized Rounded family; fall back to `Icons.Filled.AutoAwesome` to match dialog until foundation lands.

**Rationale:** Consistency with settings/LLM surfaces from `ui-polish-design-system-foundation`.

## Component / file map

| Change | File |
|--------|------|
| ComposeView cache | `ChirpKeyboardService.kt` |
| UI restructure, timer, dots, stop icon, hoisted selector | `KeyboardUI.kt` |
| Reference patterns (read-only) | `VoiceRecognitionDialog.kt` |
| Widget strings/colors | `feature-widget/.../values/strings.xml`, `values/colors.xml`, `values-night/colors.xml` |
| Widget provider | `RecordingWidgetProvider.kt` |
| Widget layout | `layout/widget_layout.xml` |
| Motion token | `core-ui/.../motion/ChirpMotion.kt` |
| New keyboard strings | `feature-keyboard/.../values/strings.xml` |

## Migration Plan

1. **ChirpMotion token** — Add `keyboardProcessingCrossfade`; no call sites yet.
2. **IME lifecycle** — Change `ChirpKeyboardService` caching; manual QA: switch fields 10×, rotate, dark mode.
3. **KeyboardUI** — Stop icon + timer + ThinkingDots + LLM chip + hoisted selector + processing crossfade in one focused PR.
4. **ModelNotReady CTA** — Add intent launcher + string; wire `onTap` / dedicated callback.
5. **Widget resources** — Add strings/colors; update layout XML and provider; grep for remaining hardcoded copy.
6. **Verification** — `./gradlew :feature-keyboard:assembleDebug :feature-widget:assembleDebug`; IME manual matrix; widget add/update on home screen.

**Rollback:** IME caching is isolated revert in service file. UI changes revert independently. Widget resource changes are backward-compatible.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Cached ComposeView retains stale state after long hide | State driven by `StateFlow` collectors; composition recomposes on state change even if view persists |
| Memory leak if view not cleared on destroy | Explicit `disposeComposition()` + `composeView = null` in `onDestroy` |
| `RecordingTimer` expects `RecordingState` not `KeyboardState` | Pass synthetic mapping or add keyboard overload accepting elapsed ms / sample count |
| Hoisted mode row changes keyboard height | Keep `heightIn(min=200, max=280)`; verify no clip on small IME height |
| Widget night colors incomplete | Ship light `colors.xml` first; `values-night` as best-effort |
| AutoAwesome vs Filled inconsistency with dialog | Accept short-term Filled match; migrate both to Rounded in design-system follow-up |

## Open Questions

- **ModelNotReady CTA destination:** Main activity home vs transcription settings download screen? **Recommendation:** Main activity with optional `EXTRA_NAVIGATE=transcription` if route exists; else plain main launch.
- **RecordingTimer data source for keyboard:** Reuse `sampleCountFlow` at 44.1 kHz sample rate vs monotonic clock from record start? **Recommendation:** Add elapsed ms to `VoiceRecorder` or derive from sample count / sample rate already known to keyboard service.
- **Widget notification icon ownership:** If no widget-specific notification exists, only add drawable resource for shared `RecordingService` error path? **Recommendation:** Add `ic_widget_notification` alias in widget module; wire in service notification builder when widget origin fails.
