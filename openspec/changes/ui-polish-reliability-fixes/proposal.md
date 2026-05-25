# Change: UI Polish — Reliability Fixes (Service Lifecycle & Playback ANR)

## Why

A UI polish audit and follow-up code review identified three medium-severity reliability defects in the recording and playback stack. Each defect can surface as silent misbehavior, unexpected recording stops, or Application Not Responding (ANR) dialogs — eroding trust during the same release cycle where studio, home, and keyboard surfaces are being polished.

**Problem 1 — RecordingService listener leak via singleton `AudioInputDeviceSelector`**

`AudioInputDeviceSelector` is a Hilt `@Singleton` that registers a process-lifetime `AudioDeviceCallback` and stores an optional `onActiveDeviceLost` callback. `RecordingService.onCreate()` registers a listener that captures `serviceScope` and calls `stopRecording()` when the active mic disconnects. When recording ends normally, `clearActiveDevice()` clears `activeDeviceId` and the device label but **did not** clear `onActiveDeviceLost`. After `RecordingService.onDestroy()`, the singleton still held a closure referencing the destroyed service's coroutine scope. If the user later unplugged a USB mic or Bluetooth headset (even while idle), the stale callback could invoke `stopRecording()` on a dead service context — or worse, retain the service graph in memory and cause confusing post-session side effects.

**Problem 2 — RecordingPlaybackService ANR on prepare-only playback**

`RecordingPlaybackController.createController()` previously called `context.startForegroundService()` before building a `MediaController`. Android requires any service started via `startForegroundService()` to call `startForeground()` within ~5–10 seconds. Studio and home screens call `prepare()` to load duration and show the mini player **without** starting playback. `RecordingPlaybackService` is a `MediaSessionService` that never promotes itself to foreground during prepare-only sessions. On Android 8+ this produces `ForegroundServiceDidNotStartInTimeException` and user-visible ANRs when opening Processing Studio or preloading playback metadata.

**Problem 3 — Audio focus callback retained after service destroy**

`RecordingService` assigns `audioFocusManager.onFocusLost` in `onCreate()` to pause or stop recording on focus loss. Without clearing that callback in `onDestroy()`, the same `@Singleton`-scoped pattern as Problem 1 applies: a destroyed service's behavior can remain wired into `AudioFocusManager`, which may still receive system focus events and attempt to invoke recording actions after the service lifecycle has ended.

These issues are **in scope now** because: (a) they were explicitly flagged in the UI audit backlog under reliability; (b) partial fixes already exist in the working tree and need spec-backed completion and verification; (c) the studio processing UX change increases prepare-only playback traffic, amplifying the ANR risk; and (d) USB/BT mic usage is a supported audio settings path, making device-lost callbacks non-theoretical.

## What Changes

### RecordingService & AudioInputDeviceSelector lifecycle hygiene

- **`AudioInputDeviceSelector.clearActiveDevice()`** SHALL null out `onActiveDeviceLost` whenever active device tracking is reset, not only when explicitly unset via `setOnActiveDeviceLostListener(null)`.
- **`RecordingService.onDestroy()`** SHALL call `inputDeviceSelector.setOnActiveDeviceLostListener(null)` before or after cancelling `serviceScope`, ensuring no service-bound closure survives service teardown regardless of which stop path ran last.
- **Stop/cancel/finalize paths** that already call `clearActiveDevice()` gain automatic listener cleanup via the selector change; `onDestroy()` remains the backstop for crash/kill paths that skip normal stop cleanup.
- Document the contract: only one active registrant (typically `RecordingService`) may set `onActiveDeviceLost` at a time; registrants MUST clear on destroy.

### RecordingPlaybackController foreground-service fix

- **Remove** manual `context.startForegroundService(Intent(..., RecordingPlaybackService::class.java))` from `createController()`.
- **Rely on Media3 `MediaController.Builder`** to connect via implicit `bindService` to `RecordingPlaybackService` — the supported pattern for `MediaSessionService` when playback may remain idle/prepared.
- **Preserve** existing prepare/play/seek/stop behavior in `RecordingPlaybackController`; no user-facing API changes.
- **Clarify** that foreground promotion for playback (if ever required for background playback) is owned by `RecordingPlaybackService` / Media3 session policy, not the controller's connect path.
- Add regression coverage that prepare-only flows do not invoke `startForegroundService`.

### AudioFocusManager callback cleanup

- **`RecordingService.onDestroy()`** SHALL set `audioFocusManager.onFocusLost = null` after abandoning focus (or as part of teardown if focus already abandoned).
- **`RecordingService` stop/cancel paths** continue to call `audioFocusManager.abandonFocus()`; destroy cleanup prevents dangling callbacks if abandon was skipped on abnormal termination.
- Audit note (non-blocking for this change): `ChirpKeyboardService` also sets `audioFocusManager.onFocusLost`; keyboard IME stability change may mirror the same pattern — out of scope unless audit finds an identical leak there.

### Specification & verification (no behavioral feature additions)

- Encode lifecycle and playback-connection requirements in `recording-stability`, `recording-ui`, and `recording` delta specs.
- Add unit/instrumentation tasks for listener clearing, prepare-without-foreground, and destroy hygiene.
- No database migrations, no new user-facing settings, no breaking public API changes.

## Capabilities

### New Capabilities

_(none — this change tightens existing recording and playback behavior; no new product surface)_

### Modified Capabilities

- **`recording-stability`**: Add normative requirements for service teardown hygiene — clearing singleton-registered audio device listeners and audio focus callbacks when `RecordingService` is destroyed; clearing device-lost listeners in `AudioInputDeviceSelector.clearActiveDevice()`.
- **`recording-ui`**: Add requirement that prepare-only playback (mini player / studio preload) connects to `RecordingPlaybackService` without triggering foreground-service start timeout; playback UI remains responsive when opening studio before pressing play.
- **`recording`**: Modify **Recording Playback** and **Recording Service Coordination** requirements to distinguish recording foreground notification (required during capture) from playback session connection (bind-only for prepare; foreground only when playback policy demands it).

## Impact

### Affected modules & files

| Area | File(s) | Change |
|------|---------|--------|
| Audio input | `core-audio/.../AudioInputDeviceSelector.kt` | Clear `onActiveDeviceLost` in `clearActiveDevice()` |
| Recording service | `feature-recording/.../RecordingService.kt` | Clear device listener + `onFocusLost` in `onDestroy()` |
| Playback | `core-playback/.../RecordingPlaybackController.kt` | Remove `startForegroundService`; bind-only MediaController connect |
| Playback service | `core-playback/.../RecordingPlaybackService.kt` | No code change expected; behavior unchanged |
| Manifest | `app/src/main/AndroidManifest.xml` | Verify service declaration remains valid for bind-only connect |
| Tests | `core-playback/.../RecordingPlaybackControllerTest.kt` | Assert no foreground start on prepare; destroy hygiene tests as applicable |

### Affected user flows

| Flow | Before | After |
|------|--------|-------|
| Open Processing Studio for a recording | Possible ANR / crash on prepare | Duration loads; mini player ready without ANR |
| Preload playback on home list | Same ANR risk when controller connects | Bind-only connect; no FGS timeout |
| Stop recording & destroy service | Stale mic-disconnect callback may fire later | Callback cleared; no ghost stop attempts |
| Unplug USB mic after session ended | Could invoke stale service closure | No-op; listener null |
| System audio focus change after service killed | Stale pause/stop callback | No-op; callback null |

### Dependencies & interaction with sibling changes

| Sibling change | Interaction |
|----------------|-------------|
| `ui-polish-studio-processing-ux` | Increases prepare-only playback usage in studio — **raises priority** of playback ANR fix |
| `ui-polish-keyboard-ime-stability` | May share audio focus listener patterns; coordinate if keyboard service audit finds parallel leak |
| `playback-package-alignment` | Already moved types to `core.playback` package; this change touches same controller file |
| `fix-medium-stability` (archived) | Established `recording-stability` spec; this change extends service lifecycle guarantees |

### Risk assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Removing `startForegroundService` breaks background playback | Medium | Media3 `MediaSessionService` supports bind-first; verify play-from-notification and background paths in manual QA |
| Over-clearing `onActiveDeviceLost` drops legitimate disconnect handling mid-recording | Low | Only clear on `clearActiveDevice()` (session end) and `onDestroy()`; re-register in `onCreate()` each service instance |
| Keyboard service has parallel focus callback leak | Low | Document in design; track under keyboard stability change if confirmed |
| Test flakiness on MediaController integration | Medium | Prefer unit-level verification (no `startForegroundService` call) plus targeted manual studio open test |

### Success criteria

1. Opening Processing Studio and calling `prepare()` never triggers `ForegroundServiceDidNotStartInTimeException`.
2. After `RecordingService` destroy, `AudioInputDeviceSelector` has no non-null `onActiveDeviceLost` unless a new service instance registered one.
3. After `RecordingService` destroy, `audioFocusManager.onFocusLost` is null.
4. `openspec status --change ui-polish-reliability-fixes` shows all artifacts complete and tasks ready for apply phase.
5. Existing `RecordingPlaybackControllerTest` and new lifecycle tests pass in CI.

### Out of scope

- New foreground notification UI for playback
- Refactoring `AudioInputDeviceSelector` to non-singleton ownership model
- Keyboard IME audio focus leak fixes (unless discovered blocking)
- Broader UI polish items from audit unrelated to these three defects
- Processing studio header/tab UX (covered by sibling changes)
