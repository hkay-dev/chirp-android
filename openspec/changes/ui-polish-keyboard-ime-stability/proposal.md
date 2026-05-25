# Change: UI polish — keyboard IME stability and voice-input alignment

## Why

The keyboard IME currently tears down its Compose tree on every `onFinishInputView` and disposes composition before recreating the view in `onCreateInputView`. Switching fields or briefly hiding the keyboard resets `ModeSelector` scroll position, drops in-flight UI state, and causes visible flicker. Keyboard recording UI diverges from the in-app `VoiceRecognitionDialog` (Check vs Stop icon, plain spinner vs `ThinkingDots`, "LLM" chip vs `AutoAwesome`, no elapsed timer). `ModelNotReady` is informational only with no action. The home-screen widget hardcodes English status strings and hex colors in Kotlin and XML. These gaps were flagged in the UI polish audit as Tier 1 for the highest-traffic input surfaces.

## What Changes

- **CRITICAL — IME lifecycle**: Stop calling `disposeComposition()` on every `onFinishInputView`; cache and reuse the `ComposeView` across input sessions; dispose only in `onDestroy`.
- **Voice input UI alignment**: Align keyboard recording/processing surfaces with `VoiceRecognitionDialog` patterns — `RecordingTimer` during capture, `ThinkingDots` during processing, `Icons.Rounded.AutoAwesome` LLM chip (replacing "LLM" + Check), and **Stop** icon (not Check) on the stop FAB.
- **Processing transitions**: Crossfade transcribing ↔ polishing using `ChirpMotion` tokens (consistent with Processing Studio header).
- **ModeSelector stability**: Hoist `ModeSelector` and LLM chip row outside `AnimatedContent` so horizontal scroll position survives state transitions between Idle and Recording.
- **ModelNotReady CTA**: Add a primary action button (open app / download model) instead of passive helper text alone.
- **Widget polish**: Move hardcoded status strings and content descriptions to `strings.xml`; replace inline hex tints with color resources where `RemoteViews` allows; use branded notification/small icon for widget-related system notifications.

## Capabilities

### New Capabilities

_(none — all changes extend existing keyboard, widget, and animation specs)_

### Modified Capabilities

- `keyboard-ux`: IME composition caching, shared voice-input visual patterns, stop-icon semantics, hoisted mode selector, ModelNotReady CTA, processing crossfade presentation.
- `keyboard-recording`: Recording timer display during keyboard capture; stop control uses Stop semantics aligned with app recording surfaces.
- `widget`: Localized widget status copy, color token usage, branded notification icon for widget error paths.
- `ui-animations`: Keyboard processing phase crossfade via `ChirpMotion`; transcribing/polishing transitions follow animation timing standards.

## Impact

- **Modules**: `feature-keyboard` (primary), `feature-widget`, `core-ui` (`ChirpMotion` token addition, existing `ThinkingDots` / `RecordingTimer` reuse).
- **Reference surfaces**: `ChirpKeyboardService.kt`, `KeyboardUI.kt`, `VoiceRecognitionDialog.kt`, `RecordingWidgetProvider.kt`, `widget_layout.xml` and related drawables.
- **Dependencies**: Assumes `ui-polish-design-system-foundation` (or equivalent) has landed `ChirpTheme` / `KeyboardTheme` delegation; no new Gradle modules.
- **Tests**: Keyboard compose/screenshot tests for stop icon, processing states, ModeSelector scroll persistence; widget unit test for string resource keys; manual IME field-switch smoke.
- **Risk**: Medium for IME lifecycle — incorrect caching could leak composition or retain stale callbacks; mitigated by dispose-on-destroy only and field-switch manual QA.

## Non-Goals

- Rewriting `VoiceRecognitionDialog` layout or extracting a full shared `VoiceInputPanel` composable (align patterns only; optional thin shared wrapper deferred).
- Changing transcription pipeline, LLM processing logic, or `RecordingStateManager` coordination rules.
- Widget profile picker or long-press configuration (future spec).
- Keyboard foreground service notification copy/icon overhaul (separate hygiene change unless trivial resource swap).
- Full widget dark/light theme via dynamic colors (RemoteViews limits — tokenize static palette only).
