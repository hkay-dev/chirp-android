## Context

Chirpboard's recording pipeline runs in a foreground `RecordingService` while playback uses a separate `RecordingPlaybackService` (`MediaSessionService` + ExoPlayer + Media3 session). Both services interact with `@Singleton` components in `:core-audio` (`AudioInputDeviceSelector`, and per-service instances of `AudioFocusManager` backed by system `AudioManager`).

### Current architecture

```
┌─────────────────────┐     registers      ┌──────────────────────────┐
│  RecordingService   │ ─────────────────► │ AudioInputDeviceSelector │
│  (per session)        │  onActiveDeviceLost│ (@Singleton)             │
└─────────────────────┘                    │  + AudioDeviceCallback   │
         │ onCreate/onDestroy              └──────────────────────────┘
         ▼                                            │
┌─────────────────────┐                               │ USB/BT unplug
│ AudioFocusManager   │◄── onFocusLost callback       ▼
│ (per service inst.) │                    may invoke stale closure
└─────────────────────┘

┌──────────────────────────┐   startForegroundService (REMOVED)
│ RecordingPlaybackController│ ───────────────────────────────► ANR if no startForeground()
└──────────────────────────┘   MediaController.Builder (bind) ──► OK for prepare-only
         │
         ▼
┌──────────────────────────┐
│ RecordingPlaybackService │
│ (MediaSessionService)    │
└──────────────────────────┘
```

### Defect summary (pre-fix)

| Defect | Root cause | Symptom |
|--------|------------|---------|
| Listener leak | `clearActiveDevice()` did not null listener; `onDestroy()` did not clear listener | Post-session mic unplug triggers ghost `stopRecording()`; memory retention |
| Playback ANR | `startForegroundService()` without subsequent `startForeground()` on prepare-only path | ANR opening studio / preload |
| Focus callback leak | `onFocusLost` not cleared on service destroy | Focus events invoke pause/stop on dead service |

### Stakeholders

- **End users**: Expect studio and home playback preload to be instant and crash-free; expect mic unplug during recording to stop gracefully, not randomly later.
- **Developers**: Need explicit lifecycle contracts for singleton-adjacent callbacks.
- **QA**: Can reproduce ANR by opening studio; can reproduce leak by recording → stop → unplug USB mic.

### Constraints

- `AudioInputDeviceSelector` remains `@Singleton` (device list + callback registration is process-scoped by design).
- `RecordingPlaybackService` must remain declared in manifest; no migration to a non-service playback model in this change.
- Must not regress actual recording foreground notification behavior (`RecordingService` still uses `startForeground` during capture).
- Align with archived `recording-stability` patterns (explicit SHALL requirements, testable scenarios).

## Goals / Non-Goals

**Goals:**

- Eliminate stale `onActiveDeviceLost` callbacks after recording session teardown.
- Eliminate `ForegroundServiceDidNotStartInTimeException` / ANR on prepare-only playback connection.
- Clear `audioFocusManager.onFocusLost` when `RecordingService` is destroyed.
- Document singleton callback ownership contract in specs and design.
- Provide verifiable unit tests and manual QA steps.

**Non-Goals:**

- Redesigning `AudioInputDeviceSelector` as session-scoped or replacing `AudioDeviceCallback` with per-recorder listeners.
- Adding playback foreground notifications for background play.
- Fixing keyboard IME focus callbacks (track separately unless identical bug confirmed).
- Changing Media3 session IDs, ExoPlayer configuration, or studio UI layout.
- Broader UI polish audit items (typography, tabs, nav shell, etc.).

## Decisions

### Decision 1: Clear listener in both `clearActiveDevice()` and `onDestroy()`

**What:** `AudioInputDeviceSelector.clearActiveDevice()` sets `onActiveDeviceLost = null`. `RecordingService.onDestroy()` also calls `setOnActiveDeviceLostListener(null)`.

**Why:** Defense in depth. Normal stop paths already call `clearActiveDevice()` (stop, cancel, emergency finalize). Centralizing nulling in `clearActiveDevice()` fixes the common path. Explicit `onDestroy()` clearing covers crash/kill paths where `clearActiveDevice()` might not run, and makes the service contract obvious to readers.

**Alternatives considered:**

| Alternative | Rejected because |
|-------------|------------------|
| Only `onDestroy()` | Misses leak if service instance stays alive briefly with idle singleton listener between sessions |
| WeakReference to service | Adds complexity; does not prevent erroneous stop calls, only GC retention |
| Session-scoped selector | Large refactor; out of scope |

### Decision 2: Bind-only MediaController connection (remove `startForegroundService`)

**What:** `RecordingPlaybackController.createController()` uses only `MediaController.Builder(context, sessionToken).buildAsync()` without starting the service as foreground first.

**Why:** Android foreground service rules require `startForeground()` within a short window after `startForegroundService()`. Prepare-only usage (load media item, read duration, show paused mini player) never calls `play()`, so the service correctly stays non-foreground. Media3 documentation and `MediaSessionService` lifecycle support controller-driven binding for local playback UIs.

**How:**

```kotlin
// REMOVED — causes ANR on prepare-only:
// context.startForegroundService(Intent(context, RecordingPlaybackService::class.java))

// CORRECT — implicit bind via MediaController:
val sessionToken = SessionToken(context, ComponentName(context, RecordingPlaybackService::class.java))
return MediaController.Builder(context, sessionToken).buildAsync().await()
```

**Alternatives considered:**

| Alternative | Rejected because |
|-------------|------------------|
| Call `startForeground()` in service `onCreate()` always | Unnecessary persistent notification for prepare-only; bad UX |
| Lazy `startForeground()` on first `play()` | Still need to avoid `startForegroundService()` on prepare path; bind-first is simpler |
| Move playback to non-service ExoPlayer in ViewModel | Breaks MediaSession integration, notification controls, and existing architecture |

### Decision 3: Null `onFocusLost` in `RecordingService.onDestroy()`

**What:** After cancelling jobs and clearing device listener, set `audioFocusManager.onFocusLost = null`.

**Why:** `AudioFocusManager` holds the listener reference until overwritten. System can deliver focus changes after service teardown if focus was not fully abandoned or race occurs. Nulling prevents invoke-after-destroy.

**Ordering:** Cancel coroutines → clear device listener → null focus callback → (super.)`onDestroy()`. `abandonFocus()` should already have run on normal stop; nulling is idempotent safety.

### Decision 4: Specification placement

**What:** Delta specs touch `recording-stability` (lifecycle), `recording-ui` (prepare UX / no ANR), and `recording` (playback vs recording service coordination).

**Why:** Stability spec already covers ANR and thread safety themes. Playback prepare behavior is user-visible in studio/mini player (`recording-ui`). Parent `recording` spec owns "Recording Playback" and "Recording Service Coordination" requirements — foreground rules must distinguish capture vs playback.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Background playback without foreground notification may be killed on aggressive OEMs | Accept for v1; local in-app playback while visible is primary use case; revisit if background play becomes requirement |
| Bind-only connect fails on very old devices | Min SDK already API 26+; Media3 session bind supported |
| Clearing listener during active recording if `clearActiveDevice()` called too early | Audit call sites — only invoke `clearActiveDevice()` on session end, not mid-capture device switch |
| Tests cannot easily instantiate real MediaController | Verify absence of `startForegroundService` via refactor inspection + instrumented test optional; mock context tests for controller state machine |

## Migration Plan

1. **Implement selector + service destroy cleanup** (low risk, no UX change).
2. **Remove `startForegroundService` from controller** (fixes ANR immediately for studio).
3. **Add/update unit tests**.
4. **Manual QA**: open studio, home mini player prepare, record with USB mic unplug after stop.
5. **Rollout**: Ship with UI polish batch; no feature flag needed.
6. **Rollback**: Revert single commits independently; restore `startForegroundService` only if bind path fails on device matrix (unlikely).

## Open Questions

- [ ] Does `ChirpKeyboardService` need identical `onFocusLost` destroy cleanup in `ui-polish-keyboard-ime-stability`?
- [ ] Should `AudioInputDeviceSelector` expose `hasActiveDeviceLostListener()` for debug assertions in debug builds?
- [ ] Is instrumented studio navigation test worth adding now or defer to screenshot/regression suite?
