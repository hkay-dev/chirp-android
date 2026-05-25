# Change: UI polish regression fixes

## Why

The May 2026 UI polish pass shipped several regressions on high-traffic flows: delayed Record→Studio navigation, duplicate processing progress on Processing Studio, home list disappearing when filters returned zero rows, and a clipped search field. These were traceable to intentional spec decisions or latent home logic exposed by new filter/banner behavior — not accidental file overwrites.

## What Changes

- **Record Done:** Restore immediate navigation via `activeRecordingId` with `hasNavigatedToComplete` dedupe; keep `lastCompletedRecordingId` as fallback.
- **Studio progress:** Transcript tab shows skeleton only during pipeline states; header compact banner is the single morphing progress surface.
- **Home filters:** `showEmptyState` keyed on `stats.totalRecordings == 0`, not filtered `displayItems`; inline filtered-empty UI with clear affordance; processing pill cannot activate empty filter.
- **Home search:** Search field docked below `MediumTopAppBar`; collapse disabled while search active.
- **Docs:** Correct `ui-polish-nav-shell-stability` and `ui-polish-studio-processing-ux` designs/specs; add `openspec/UI_POLISH_QA_CHECKLIST.md` as pre-merge gate.

## Capabilities

### Modified Capabilities

- `recording-ui`: Done handoff, home filter empty states, search layout
- `transcription`: Single progress surface on Processing Studio

## Impact

- **Modules:** `feature-recording`, `feature-studio`, `core-ui`
- **Risk:** Low — UI-only corrections; no schema or service changes

## Non-Goals

- Reverting unrelated UI polish (mini player animation, skeleton shell, metadata unification)
- Automated screenshot tests (manual checklist only)
