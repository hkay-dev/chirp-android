# Tasks: UI polish — keyboard IME stability and voice-input alignment

## 1. ChirpMotion token

- [x] 1.1 Add `keyboardProcessingCrossfade: ContentTransform` to `ChirpMotion.kt` (250 ms fade-in, 200 ms fade-out, `FastOutSlowInEasing`)
- [x] 1.2 Document token in KDoc with keyboard transcribing/polishing use case

## 2. IME composition lifecycle (CRITICAL)

- [x] 2.1 Remove `composeView?.disposeComposition()` from start of `onCreateInputView()`
- [x] 2.2 Return cached `composeView` when non-null; create and assign only on first call
- [x] 2.3 Remove `disposeComposition()` and `composeView = null` from `onFinishInputView()`
- [x] 2.4 Add `composeView?.disposeComposition()` and null assignment in `onDestroy()` before recorder close
- [ ] 2.5 Manual QA: switch text fields 10× with mode chips scrolled — verify scroll offset preserved

## 3. KeyboardUI — stop icon and recording timer

- [x] 3.1 Replace `Icons.Filled.Check` with `Icons.Filled.Stop` on recording stop FAB
- [x] 3.2 Add `RecordingTimer` to recording layout (derive elapsed from sample count/rate or service timing)
- [x] 3.3 Verify `keyboard_desc_stop_recording` content description remains accurate for Stop icon

## 4. KeyboardUI — processing and LLM chip alignment

- [x] 4.1 Replace `ProcessingContent` spinner with `ThinkingDots` + status text
- [x] 4.2 Group `Transcribing` and `Polishing` under processing branch with `AnimatedContent` using `ChirpMotion.keyboardProcessingCrossfade`
- [x] 4.3 Update `LlmToggle`: `AutoAwesome` leading icon when enabled; mode-aware label (mirror dialog pattern); remove literal "LLM" + Check
- [x] 4.4 Add/adjust strings in `feature-keyboard/.../values/strings.xml` for LLM chip labels if needed

## 5. KeyboardUI — hoist ModeSelector

- [x] 5.1 Extract mode row (`LlmToggle` + divider + `ModeSelector`) from `IdleContent` and `RecordingContent`
- [x] 5.2 Place hoisted row below `AnimatedContent` in `KeyboardUI`; single `rememberScrollState()` at parent scope
- [x] 5.3 Show row only when `state is Idle || state is Recording` and LLM enabled with valid mode
- [ ] 5.4 Verify keyboard height constraints (`heightIn min/max`) still satisfied

## 6. ModelNotReady CTA

- [x] 6.1 Add `FilledTonalButton` to `ModelNotReadyContent` with open-app/download label
- [x] 6.2 Wire callback from `KeyboardUI` through `ChirpKeyboardService` to launch `MainActivity` (optional navigation extra)
- [x] 6.3 Preserve existing tap-to-retry on mic/action path for `ModelNotReady`

## 7. Widget i18n and colors

- [x] 7.1 Add string resources: `widget_title`, `widget_status_idle`, `widget_status_starting`, `widget_status_saving`, `widget_status_error`, `widget_desc_toggle_recording`
- [ ] 7.2 Add `values/colors.xml` (and optional `values-night/colors.xml`): `widget_surface`, `widget_on_surface`, `widget_on_surface_variant`, `widget_accent_recording`, `widget_accent_inactive`
- [x] 7.3 Update `widget_layout.xml` to reference string/color resources instead of literals
- [x] 7.4 Update `RecordingWidgetProvider.kt` to use `context.getString()` and `context.getColor()` for all status text and tints
- [ ] 7.5 Add branded notification small icon drawable (or reference app `ic_stat_*`); wire in widget/recording error notification builder if path exists

## 8. Verification

- [ ] 8.1 Manual IME matrix: field switch, hide/show keyboard, dark mode, recording → transcribing → polishing flow
- [ ] 8.2 Manual widget: idle/record/stop/error states show localized strings and correct tints
- [ ] 8.3 Compare keyboard stop icon and LLM chip side-by-side with `VoiceRecognitionDialog`
- [x] 8.4 Run `./gradlew :feature-keyboard:assembleDebug :feature-widget:assembleDebug :core-ui:assembleDebug`
