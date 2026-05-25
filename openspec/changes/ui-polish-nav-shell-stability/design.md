# Design: UI polish — nav shell stability

## Context

Chirpboard's main app uses a single `NavHost` inside a `Column` with an optional global `RecordingMiniPlayerBar` below it. Playback visibility is derived from `shouldShowGlobalMiniPlayer()`. Today the mini player is toggled with a plain `if (showGlobalMiniPlayer)` — the bar appears instantly and the column height jumps, which can clip or shift tab content on the frame playback starts.

Shared-audio handoff (`SharedAudioHandoffViewModel`) overlays the entire `Box` with `LoadingState` during intake. That unmounts visual continuity with the home screen and produces a hard cut rather than a modal overlay.

`RecordScreen` currently navigates on Done from two places:
1. `RecordingActionRow.onStopRecording` calls `onRecordingComplete(recordingId)` synchronously using `recordingState.activeRecordingId`.
2. `LaunchedEffect(lastCompletedRecordingId)` calls `onRecordingComplete` after `RecordingStateManager` publishes the persisted ID.

Both can fire for the same stop action, causing double `navigate()` calls (studio opened twice or back-stack corruption). Historical autopsy (`docs/archive/NAVIGATION_AUTOPSY.md`) documented related `LaunchedEffect` cancellation issues; the fix is to consolidate on one authoritative completion signal.

`VoiceRecognitionDialog` delays visibility 50ms (`delay(50)` then `isVisible = true`), causing a blank frame. Cancel sets `isVisible = false` and calls `onCancel()` immediately but does not wait for exit animation before teardown — unlike the dismiss path which waits 250ms before `onDismissComplete()`. Glow and waveform use raw `if (isRecording)` rather than `AnimatedVisibility`.

Recovery prompts on Home and Record still use stock `AlertDialog` while cancel/restart/back dialogs on Record already use `AnimatedAlertDialog`.

`RecordingMiniPlayerBar` conditionally renders `MiniPlayerSeekTrack` without animation when `durationMs > 0`.

### Constraints

- Reuse `ChirpMotion` tokens where durations align; do not introduce ad-hoc magic numbers unless keyboard sheet physics require springs (existing pattern in `VoiceRecognitionDialog`).
- No changes to persistence, transcription queue, or recording service contracts.
- Startup prompt gating (`onStartupPromptGateChanged`) must remain correct while shared-audio overlay is visible.

## Goals / Non-Goals

**Goals:**

- Animate global mini player show/hide and column height changes in the nav shell.
- Present shared-audio loading as a fade/scrim overlay without unmounting `NavHost`.
- Guarantee exactly one navigation to Processing Studio (or configured completion target) per successful Record Done.
- Align voice-recognition sheet entry, cancel, dismiss, glow, and waveform with app animation standards.
- Use `AnimatedAlertDialog` for session recovery on Home and Record.
- Animate mini player seek track visibility.

**Non-Goals:**

- Changing nav transition curves between screens (already tuned in `AppNavigation`).
- Rewriting `RecordingMiniPlayerBar` layout or playback controller logic.
- Studio screen animation polish (separate change).
- Keyboard IME layout outside `VoiceRecognitionDialog`.
- New screenshot/regression test infrastructure (manual verification only unless existing tests cover touched composables).

## Decisions

### 1. Mini player: `AnimatedVisibility` + `animateContentSize` on nav column

**Choice:** Wrap `RecordingMiniPlayerBar` in `AnimatedVisibility(visible = showGlobalMiniPlayer, enter = slideInVertically + fadeIn, exit = slideOutVertically + fadeOut)` and add `Modifier.animateContentSize()` to the outer `Column` that holds `NavHost` + mini player.

**Rationale:** Matches `recording-ui` spec ("player slides in from bottom over 300ms with fade") and `ui-animations` content-size requirement. `animateContentSize` prevents the `NavHost` from snapping height when the bar mounts.

**Alternatives considered:**
- Animate only the bar, not the column — rejected; content still jumps because sibling height changes instantly.
- Keep bar in a `Box` overlay — rejected; would overlap nav content and complicate inset handling.

**Timing:** Use `ChirpMotion.studioRevealTransition` / `studioHideTransition` or equivalent 300ms standard tier (`tween(300, FastOutSlowInEasing)`) for consistency with sticky player spec.

### 2. Shared-audio overlay: scrim + card, NavHost stays mounted

**Choice:** Replace the `when (sharedAudioState)` branch that renders full-screen `LoadingState` with:
- Always render `NavHost` (and mini player logic) underneath.
- When `Loading` or `Failure`, render an `AnimatedVisibility` scrim (`Box` with semi-opaque `surfaceScrim`) and centered content (spinner + message, or failure UI).
- Use `fadeIn`/`fadeOut` on scrim; optional subtle scale on card.

**Rationale:** Preserves back-stack and tab state; avoids flash of empty root. Failure state already has custom UI — wrap the same content in the overlay pattern.

**Alternatives considered:**
- `Crossfade` between NavHost and LoadingState — rejected; still unmounts one branch.
- Blocking dialog — rejected; share intake is not confirm/cancel, it's progress.

**Gating:** `onStartupPromptGateChanged` logic unchanged — prompts remain suppressed while `sharedAudioState != Idle` or navigation target pending.

### 3. Record Done: immediate navigation with dedupe (corrected)

**Original choice (regressed UX):** Remove synchronous `onRecordingComplete` from `onStopRecording` and navigate only via `LaunchedEffect(lastCompletedRecordingId)`. That prevented double `navigate()` but left users on Record until background finalize completed — unacceptable for record→studio handoff.

**Corrected choice:** Navigate immediately on Done using `recordingState.activeRecordingId`, and keep `LaunchedEffect(lastCompletedRecordingId)` as a **fallback only** when immediate ID is unavailable. Guard with `hasNavigatedToComplete` so exactly one navigation fires per stop:

```kotlin
var hasNavigatedToComplete by remember { mutableStateOf(false) }

onStopRecording = {
    val recordingId = recordingState.activeRecordingId
    viewModel.stopRecording()
    if (recordingId != null && !hasNavigatedToComplete) {
        hasNavigatedToComplete = true
        onRecordingComplete(recordingId.toString())
    }
}

LaunchedEffect(lastCompletedRecordingId) {
    if (hasNavigatedToComplete) {
        viewModel.clearLastCompletedRecordingId()
        return@LaunchedEffect
    }
    val recordingId = lastCompletedRecordingId ?: return@LaunchedEffect
    hasNavigatedToComplete = true
    onRecordingComplete(recordingId.toString())
    viewModel.clearLastCompletedRecordingId()
}
```

**Rationale:** Product requires instant Processing Studio with finalizing/stitching status. Dedupe at the screen layer fixes double-navigation without delaying UX. Clearing `lastCompletedRecordingId` after navigation (not before) avoids `LaunchedEffect` cancellation mid-flight (see NAVIGATION_AUTOPSY).

**Alternatives considered:**
- LaunchedEffect-only — rejected after release regression; too slow.
- Dedupe only in `AppRecordingNavigation` — rejected; treats symptom; screen must still navigate immediately.

**AppRecordingNavigation:** No route change required; verify `onRecordingComplete` still opens studio with `launchSingleTop` and back stack is sane.

**Verification:** See `openspec/UI_POLISH_QA_CHECKLIST.md` — Record → Done → Studio section.

### 4. VoiceRecognitionDialog: symmetric lifecycle + animated sub-regions

**Choice:**
- Remove `delay(50)`; set `isVisible = true` in `LaunchedEffect(Unit)` immediately (or initialize `isVisible = true` and call `onStart()` once).
- Extract shared exit helper: on cancel OR dismiss, set `isVisible = false`, `delay(exitDuration)`, then call `onDismissComplete()` / cancel callback ordering documented in tasks.
- Wrap `RecordingGlowBackground` and `AudioWaveform` in `AnimatedVisibility(visible = isRecording && !isProcessing)` with fade enter/exit.

**Rationale:** Eliminates blank flash; cancel and dismiss feel identical. Animated sub-regions match `recording-ui` glow/waveform transition requirements.

**Exit duration:** Match existing exit tween (~150ms fade + spring slide); total wait ≈250ms (already used on dismiss path).

### 5. Recovery dialogs: drop-in `AnimatedAlertDialog`

**Choice:** Replace `AlertDialog` with `AnimatedAlertDialog` in `HomeScreen` and `RecordScreen` recovery blocks; keep button layout and copy unchanged.

**Rationale:** `AnimatedAlertDialog` is the project standard (see `AnimatedDialog.kt`); satisfies interactive feedback spec (fade + scale 250ms).

### 6. Mini player seek track: `AnimatedVisibility`

**Choice:** In `RecordingMiniPlayerBar`, wrap `MiniPlayerSeekTrack` in `AnimatedVisibility(visible = state.durationMs > 0 && state.errorMessage == null, enter = expandVertically + fadeIn, exit = shrinkVertically + fadeOut)`.

**Rationale:** Spec requires smooth player state changes; seek bar popping causes layout shift in the bar.

**Parent column:** Optionally add `animateContentSize()` on the bar's inner `Column` for coordinated height animation.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Double navigation if both Done paths remain | `hasNavigatedToComplete` guard + manual Done flow test (see UI polish QA checklist) |
| `LaunchedEffect` cancelled before navigate | Clear `lastCompletedRecordingId` only after `onRecordingComplete` |
| Shared-audio scrim allows accidental nav taps | Scrim consumes clicks; use full-size clickable overlay with `indication = null` |
| `AnimatedVisibility` mini player + `animateContentSize` jank on low-end devices | Use GPU-friendly slide/fade only; avoid simultaneous complex nav transitions |
| Cancel waits 250ms before service teardown | Document that `onCancel()` triggers stop immediately; only UI teardown waits (match dismiss) |
| Seek track animates while duration loads from 0 | Visibility keyed on `durationMs > 0`; loading state shows bar without seek until ready |

## Migration Plan

1. Implement nav shell animations (`AppNavigation.kt`) — no feature flags.
2. Shared-audio overlay — verify share intent from Files/Messages on device.
3. Record navigation singularity — regression test Done → Studio → back.
4. VoiceRecognitionDialog — manual keyboard dictation smoke test.
5. Recovery dialogs — visual check on simulated interrupted session.
6. Mini player seek — start playback from Home list, verify scrubber animates in.

**Rollback:** Revert single commit/PR; no schema or data migration.

## Open Questions

- None blocking. Optional follow-up: extract shared `SessionRecoveryDialog` composable used by Home and Record to deduplicate (out of scope unless duplication becomes maintenance burden during implementation).
