# Design: Studio processing UX polish

## Context

`ProcessingStudioScreen` is the destination after recording completes or when the user opens a recording from the list. Today it gates the entire composable on `state.isLoading`:

```kotlin
if (state.isLoading) {
    LoadingState()
    return
}
```

While `loadRecording()` waits for the first `combine()` emission, users see a blank spinner instead of the familiar studio chrome. Once loaded, transcription-in-progress states show progress in `StudioProcessingHeader` but `TranscriptTab` renders an empty weighted `Box` because `showTranscriptChrome` is false and no processing branch exists.

Layout animation is split across two nested `animateContentSize` modifiers (screen root column and `StudioProcessingHeader` column). When progress hides and the player appears—or vice versa—the double spring causes overshoot and pager height jumps.

Playback is prepared in `ProcessingStudioViewModel.scheduleDeferredStudioPlayback()` with `delay(ChirpMotion.NAV_TRANSITION_MS)` (450 ms), but `ChirpMotion.RECORD_HANDOFF_MS` (480 ms) was introduced specifically for record→studio handoff. The screen shows the player slot as soon as `audioPath` is non-blank, desynchronizing visible chrome from playback readiness.

**Constraints**

- Jetpack Compose + Material 3; motion tokens live in `ChirpMotion`.
- No destructive DB changes; ViewModel continues to use existing repository flows.
- Tier 1 scope — minimize new public API surface; prefer internal composables and state flags.

## Goals / Non-Goals

**Goals:**

- Valid recording navigation always shows studio shell immediately (top bar, tabs, placeholders).
- Transcript tab never shows an empty body during pipeline states.
- One layout-size animation owner; stable player region height during progress/player crossfade.
- Tab switches without first-frame flash on adjacent pages.
- Transcript chrome uses proper visibility transitions (not alpha-only).
- Progress UI communicates phase via distinct icons.
- Player defer and visible player slot share `RECORD_HANDOFF_MS`.

**Non-Goals:**

- New loading component library or cross-app skeleton system.
- Rewriting `RecordingFullPlayer`, transcription workers, or navigation args.
- Summary/Chat tab content changes beyond pager prefetch.
- Estimated-time-remaining for transcription (existing spec scenario is aspirational; out of scope here).

## Decisions

### D1: Loading state split — skeleton vs full-screen

| Approach | Pros | Cons |
|----------|------|------|
| **A. Skeleton for valid ID, `LoadingState` only for bad ID** (chosen) | Preserves wayfinding; matches audit Tier 1 | Requires ID validation before branch |
| B. Always skeleton, never `LoadingState` | Simpler | Bad-ID UX unclear |
| C. Keep full-screen load | No work | Audit failure |

**Decision:** Parse `recordingId` to `UUID?` at screen entry (`screenRecordingId`). Branch:

- `screenRecordingId == null` (or empty / `-1` nav arg) → full-screen `LoadingState` (optionally with short message); do not call `loadRecording`.
- Valid UUID → always render `Scaffold` + skeleton when `state.isLoading || state.title.isBlank()` (initial fetch), populating real content as flows emit.

Extract `ProcessingStudioSkeleton` (internal composable in `ProcessingStudioScreen.kt` or `StudioProcessingHeader.kt` companion file) with:

- Real `TopAppBar` + back (functional).
- Real `PrimaryTabRow` with transcript/summary/chat labels (tabs non-interactive or interactive — prefer interactive pager even during load).
- Placeholder title line (~70% width, `surfaceVariant` rounded rect).
- Three chip placeholders (date, duration, source).
- Optional header progress placeholder bar when status already known from partial state.

### D2: Transcript tab processing fallback

| Approach | Pros | Cons |
|----------|------|------|
| **A. `TranscriptionProgressPanel` when processing and no transcript chrome** (chosen) | Reuses existing morphing progress; consistent with header | Slightly redundant with compact header banner |
| B. Skeleton lines only | Lighter | Less informative |
| C. Full-screen blocking overlay | Very visible | Hides header progress; worse on small screens |

**Decision:** In `TranscriptTab`, add branch when `isProcessing && !isEditingTranscript`:

- If `transcriptionProgressCopy()` non-null → `TranscriptionProgressPanel` centered in weighted `Box`.
- Else → 4–6 skeleton text lines (fixed height bars, `surfaceVariant`).

When processing completes, crossfade to transcript via `AnimatedVisibility` (D5).

Header compact banner (`StudioProcessingHeader`) remains; tab panel is the **body** fallback, not a duplicate full-screen takeover.

### D3: Single `animateContentSize` owner + fixed player slot

**Decision:**

- **Keep** `animateContentSize(ChirpMotion.layoutSizeSpring)` on the **studio root column** in `ProcessingStudioScreen` (the column inside `Scaffold` content).
- **Remove** `animateContentSize` from `StudioProcessingHeader`.
- Wrap player region in a `Box(Modifier.fillMaxWidth().heightIn(min = playerSlotMinHeight))` where `playerSlotMinHeight` is a constant (~88–96 dp, tuned to `RecordingFullPlayer` compact height + dividers).

`StudioProcessingHeader` continues to use `AnimatedVisibility` for progress banner and player; the min-height slot absorbs height delta so the pager below does not jump.

Progress and player are **mutually exclusive** in normal pipeline flow (progress while transcribing, player after audio ready). Slot min-height covers the larger of the two.

### D4: Pager prefetch

**Decision:** Change `beyondViewportPageCount` from `0` to `1` on the studio `HorizontalPager`. No change to page count or offscreen limit beyond default.

### D5: `AnimatedVisibility` for transcript chrome

**Decision:** Remove `animateFloatAsState` + `graphicsLayer { alpha }` pattern from `TranscriptTab`.

Replace with:

- `AnimatedVisibility(visible = showTranscriptChrome, enter = progressEnterTransition, exit = progressExitTransition)` wrapping copy actions, manual-correction banner, and transcript body.
- Separate `AnimatedVisibility` for `isEditingTranscript` editor (instant or same transitions — prefer same for consistency).
- Processing fallback (D2) uses its own visibility block with `visible = isProcessing && !isEditingTranscript`.

Reuse existing `progressEnterTransition` / `progressExitTransition` from `TranscriptionProgressUi.kt` for consistency with header.

### D6: Phase-specific progress icons

**Decision:** Extend `MorphingTranscriptionProgress` to accept `TranscriptionProgressKind` (or derive from copy mapping). Leading icon per phase:

| Phase | Icon | Notes |
|-------|------|-------|
| Finalizing | `Icons.Default.Mic` or `Save` | Recording wrap-up |
| Transcribing | `Icons.Default.GraphicEq` or `Subtitles` | Audio→text |
| Enhancing | `Icons.Default.AutoAwesome` | LLM pass |

Keep a small `CircularProgressIndicator` beside or overlaying icon in compact mode; column mode may show icon above spinner. Icons animate via existing `AnimatedContent(targetState = kind)` in `StudioProcessingHeader`.

Pass `kind` from call sites: `StudioProcessingHeader`, `TranscriptionProgressPanel`, `AnimatedTranscriptionProgress`.

### D7: `RECORD_HANDOFF_MS` wiring

**Decision:**

1. **ViewModel:** In `scheduleDeferredStudioPlayback`, replace `ChirpMotion.NAV_TRANSITION_MS` with `ChirpMotion.RECORD_HANDOFF_MS`.
2. **ViewModel state:** Add `playerRevealReady: Boolean` (default false). On `loadRecording`, reset to false. Launch coroutine: `delay(RECORD_HANDOFF_MS)` then set true if `currentRecordingId` unchanged.
3. **Screen:** `showPlayer` becomes:
   ```kotlin
   showPlayer = screenRecordingId != null &&
       state.playerRevealReady &&
       state.audioPath.isNotBlank() &&
       state.status != RecordingStatus.RECORDING
   ```
4. Progress banner visibility unchanged (shows immediately when `transcriptionProgressKind() != null`).

This aligns visible player with `playbackController.onStudioOpened()` timing and eliminates the 30 ms skew vs nav transition constant.

**Alternative considered:** `LaunchedEffect` delay in composable only — rejected because playback prep must stay in ViewModel for lifecycle correctness.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Skeleton flashes real content abruptly | Keep skeleton until `isLoading == false`; crossfade metadata fields with short alpha or `AnimatedContent` on title only |
| Min-height player slot wastes space when neither progress nor player shown | Use min height only when `progressKind != null \|\| showPlayer`; otherwise `heightIn(min = 0.dp)` |
| Dual progress (header + tab panel) feels redundant | Tab panel uses fullscreen panel variant only when body would otherwise be empty; header stays compact |
| `beyondViewportPageCount = 1` increases composition cost | Only 3 tabs; acceptable for studio |
| Bad recording ID shows eternal spinner | After load completes with null recording, transition to error empty state (future); document as open question |

## Migration Plan

1. Ship behind no feature flag — pure UI swap.
2. Verify manually: cold open studio, record→studio handoff, transcribing/enhancing phases, tab switches, player appear after ~480 ms.
3. Run existing `feature-studio` androidTests; add tests for skeleton and processing fallback.
4. Rollback: revert single PR; no data migration.

## Open Questions

- Should invalid UUID show `LoadingState` indefinitely or navigate back with snackbar? **Proposal:** brief `LoadingState` then error snackbar via existing message flow (implementer choice if message infra exists).
- Should skeleton tabs be scrollable/interactive before data loads? **Default:** tabs functional; pager shows skeleton/empty tab bodies.
- Exact `playerSlotMinHeight` dp value — tune against `RecordingFullPlayer` in layout inspector during implementation.
