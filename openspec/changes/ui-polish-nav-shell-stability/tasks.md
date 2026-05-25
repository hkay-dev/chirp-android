# Tasks: UI polish — nav shell stability

## 1. App navigation shell

- [x] 1.1 In `AppNavigation.kt`, add `Modifier.animateContentSize()` to the `Column` wrapping `NavHost` and mini player
- [x] 1.2 Wrap `RecordingMiniPlayerBar` in `AnimatedVisibility` keyed on `showGlobalMiniPlayer` with slide-in-from-bottom + fade enter and slide-out + fade exit using `ChirpMotion` standard durations (300ms enter / 260ms exit)
- [x] 1.3 Verify `NavHost` and current destination stay mounted when mini player toggles (no scroll reset on Home)

## 2. Shared-audio import overlay

- [x] 2.1 Refactor `AppNavigation.kt` shared-audio `when` branch: keep `NavHost` + mini player always composed; overlay loading/failure above via full-size `Box`
- [x] 2.2 Implement scrim + centered loading card with `AnimatedVisibility` fade for `SharedAudioIntakeState.Loading` (replace full-screen `LoadingState` swap)
- [x] 2.3 Wrap existing `SharedAudioIntakeFailure` UI in the same scrim overlay pattern with matching enter/exit transitions
- [x] 2.4 Confirm `onStartupPromptGateChanged` still suppresses prompts during loading/failure/navigation target pending

## 3. Record Done — immediate navigation with dedupe (corrected)

- [x] 3.1 In `RecordScreen.kt`, restore synchronous `onRecordingComplete(activeRecordingId)` on Done with `hasNavigatedToComplete` guard
- [x] 3.2 Keep `LaunchedEffect(lastCompletedRecordingId)` as fallback only; clear ID after navigate; skip if already navigated
- [x] 3.3 Manual test: Done on active recording → immediate Studio navigation, exactly one stack entry (see `openspec/UI_POLISH_QA_CHECKLIST.md`)
- [x] 3.4 ~~Remove inline navigate from onStopRecording~~ — reverted; original task caused release regression

## 4. VoiceRecognitionDialog polish

- [x] 4.1 Remove `delay(50)` blank-frame workaround; show sheet on first frame (`isVisible = true` immediately or initial state true)
- [x] 4.2 Unify cancel and dismiss exit paths: set `isVisible = false`, await exit duration (~250ms), then invoke teardown callback (mirror existing dismiss `LaunchedEffect`)
- [x] 4.3 Wrap `RecordingGlowBackground` in `AnimatedVisibility` keyed on active recording (not processing)
- [x] 4.4 Wrap `AudioWaveform` in `AnimatedVisibility` with matching enter/exit; adjust spacer logic so layout does not jump
- [x] 4.5 Smoke test keyboard dictation: open sheet (no flash), cancel (smooth exit), stop/dismiss (same exit feel)

## 5. Recovery dialogs → AnimatedAlertDialog

- [x] 5.1 Replace recovery `AlertDialog` with `AnimatedAlertDialog` in `HomeScreen.kt` (swap import; keep buttons/copy unchanged)
- [x] 5.2 Replace recovery `AlertDialog` with `AnimatedAlertDialog` in `RecordScreen.kt`
- [x] 5.3 Visual check: recovery prompt on Home and Record animates in consistently with cancel/restart/back dialogs on Record

## 6. Mini player seek track animation

- [x] 6.1 In `RecordingMiniPlayerBar.kt`, wrap `MiniPlayerSeekTrack` in `AnimatedVisibility` (`visible = durationMs > 0 && errorMessage == null`) with `expandVertically` + fade enter and `shrinkVertically` + fade exit
- [x] 6.2 Add `animateContentSize()` on mini player inner `Column` if seek track expand still causes control row jump
- [x] 6.3 Manual test: start playback from list → seek track fades/expands in; stop → seek track animates out before bar hides

## 7. Verification

- [x] 7.1 Run `./gradlew :app:compileDebugKotlin :feature-recording:compileDebugKotlin :core-playback:compileDebugKotlin` — clean compile
- [x] 7.2 Device smoke: share audio into app (overlay fade, navigate to studio), record Done flow, global mini player show/hide while browsing Home
- [x] 7.3 Before future UI polish merges: complete `openspec/UI_POLISH_QA_CHECKLIST.md` on device
