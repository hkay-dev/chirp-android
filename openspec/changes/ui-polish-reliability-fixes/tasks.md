# Tasks: UI Polish — Reliability Fixes

## 1. AudioInputDeviceSelector listener hygiene

- [ ] 1.1 Open `core-audio/src/main/java/dev/chirpboard/app/core/audio/AudioInputDeviceSelector.kt` and locate `clearActiveDevice()`
- [ ] 1.2 Add `onActiveDeviceLost = null` to `clearActiveDevice()` alongside `activeDeviceId` and `_activeDeviceLabel` reset
- [ ] 1.3 Verify `setOnActiveDeviceLostListener(null)` remains the explicit API for callers that only need to clear the listener without resetting device id
- [ ] 1.4 Audit all `clearActiveDevice()` call sites in `RecordingService` (stop, cancel, emergency finalize) — confirm they run on every session end path
- [ ] 1.5 Add KDoc on `clearActiveDevice()` stating it clears session-scoped listener registration
- [ ] 1.6 Grep codebase for other `setOnActiveDeviceLostListener` registrants; confirm only `RecordingService` registers during capture

## 2. RecordingService onDestroy cleanup

- [ ] 2.1 Open `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingService.kt`
- [ ] 2.2 In `onDestroy()`, after cancelling jobs and before or after `serviceScope.cancel()`, call `inputDeviceSelector.setOnActiveDeviceLostListener(null)`
- [ ] 2.3 In `onDestroy()`, set `audioFocusManager.onFocusLost = null` after listener clear
- [ ] 2.4 Confirm destroy ordering: cancel jobs → cancel scope → clear device listener → null focus callback → `super.onDestroy()`
- [ ] 2.5 Verify normal stop paths still call `audioFocusManager.abandonFocus()` and `inputDeviceSelector.clearActiveDevice()` before destroy
- [ ] 2.6 Manual test: record → stop → unplug USB mic → confirm no log lines from stale `stopRecording()` on destroyed service

## 3. RecordingPlaybackController ANR fix

- [ ] 3.1 Open `core-playback/src/main/java/dev/chirpboard/app/core/playback/RecordingPlaybackController.kt`
- [ ] 3.2 Remove `context.startForegroundService(Intent(context, RecordingPlaybackService::class.java))` from `createController()`
- [ ] 3.3 Remove unused `Intent` import if no longer referenced
- [ ] 3.4 Add comment in `createController()` explaining bind-only MediaController connect and prepare-only foreground policy (reference design decision)
- [ ] 3.5 Confirm `MediaController.Builder(context, sessionToken).buildAsync()` remains the sole connection mechanism
- [ ] 3.6 Verify `AndroidManifest.xml` still declares `RecordingPlaybackService` with appropriate `exported`/`foregroundServiceType` attributes for bind + optional future foreground play
- [ ] 3.7 Manual test: open Processing Studio → confirm no ANR within 5s and duration loads in transport/mini player
- [ ] 3.8 Manual test: home screen → prepare inline/mini player → confirm no ANR and scroll stays smooth
- [ ] 3.9 Manual test: after prepare, tap play → confirm audio plays, seek works, stop clears state

## 4. Unit tests — playback controller

- [ ] 4.1 Open `core-playback/src/test/java/dev/chirpboard/app/core/playback/RecordingPlaybackControllerTest.kt`
- [ ] 4.2 Add test `prepare_doesNotStartForegroundService` (mock context; verify `startForegroundService` never called when prepare invoked)
- [ ] 4.3 Add test or static analysis note documenting bind-only connect expectation in test KDoc
- [ ] 4.4 Ensure existing tests `initialState_isIdle`, `prepare_missingAudioFile_surfacesErrorAndStaysInactive`, `stop_clearsActivePlaybackState` still pass
- [ ] 4.5 Run `:core-playback:testDebugUnitTest` and fix any failures

## 5. Unit tests — device selector lifecycle (optional but recommended)

- [ ] 5.1 Add `AudioInputDeviceSelectorTest` in `core-audio` if module has test source set; otherwise document manual verification in PR
- [ ] 5.2 Test: after `setOnActiveDeviceLostListener { }` then `clearActiveDevice()`, invoking internal callback path does not run handler (may require test hook or package-visible test)
- [ ] 5.3 Test: `setOnActiveDeviceLostListener(null)` and `clearActiveDevice()` both leave listener unset

## 6. Integration & regression verification

- [ ] 6.1 Run full unit test suite for affected modules (`:core-audio`, `:core-playback`, `:feature-recording`)
- [ ] 6.2 Run `./gradlew assembleDebug` and confirm clean build
- [ ] 6.3 Studio flow: open recording with transcript → prepare → play → seek → navigate away → stop
- [ ] 6.4 Recording + mic disconnect: start with USB mic → unplug during recording → confirm error stop; stop session → unplug again → no ghost stop
- [ ] 6.5 Audio focus: start recording → receive phone call / other app audio → confirm pause/stop; end session → trigger focus loss → no crash
- [ ] 6.6 Check logcat for `ForegroundServiceDidNotStartInTimeException` during studio open — must be absent

## 7. Documentation & OpenSpec closure

- [ ] 7.1 Confirm delta specs under `openspec/changes/ui-polish-reliability-fixes/specs/` match implemented behavior
- [ ] 7.2 Run `openspec status --change ui-polish-reliability-fixes` — all four artifacts complete before apply phase
- [ ] 7.3 After implementation, archive change per OpenSpec workflow and merge requirements into `openspec/specs/recording-stability/`, `recording-ui/`, and `recording/`
- [ ] 7.4 If keyboard IME focus leak confirmed, file follow-up task under `ui-polish-keyboard-ime-stability` (cross-reference design open question)

## 8. Code review checklist

- [ ] 8.1 No new `startForegroundService` calls added for prepare-only paths anywhere in playback stack
- [ ] 8.2 Every `setOnActiveDeviceLostListener(nonNull)` has matching clear on destroy or `clearActiveDevice()`
- [ ] 8.3 No `@Singleton` callback left capturing `RecordingService` or `serviceScope` after destroy
- [ ] 8.4 Changes limited to reliability scope — no unrelated studio UI or design-system edits in same commit unless already staged
