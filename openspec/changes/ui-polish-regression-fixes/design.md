# Design: UI polish regression fixes

## Root cause summary

| Symptom | Introduced by | Mechanism |
|---------|---------------|-----------|
| Done does not open Studio immediately | `ad0fdee` / nav-shell spec D3 | LaunchedEffect-only navigation waited for background finalize |
| Duplicate progress spinner | `dfcd914` / studio spec D2 | Header banner + tab `TranscriptionProgressPanel` by design |
| Pills make list vanish | Latent since `2912709`; exposed by filter UX | `showEmptyState = displayItems.isEmpty()` treated filtered-empty as global-empty |
| Search clipped | `d7dd57d` layout; not fixed in polish pass | `SearchBarDefaults.InputField` inside collapsing `MediumTopAppBar` title |

No git revert or accidental overwrite — sequential polish commits with incomplete integration QA.

## Decisions

### Home empty state vs filtered empty

**Choice:** `showEmptyState` only when `stats.totalRecordings == 0 && listFilter == ALL && searchQuery.isBlank()`.

When filters/search yield zero rows but recordings exist, show stats row + inline message + **Clear filter** (`clearListFilters()`).

Processing pill toggles filter only when `allRecordingsList` contains processing statuses; show selected styling on pill and dismissible `InputChip`.

### Home search layout

**Choice:** Always render title in `MediumTopAppBar`; when `searchActive`, render `InputField` in a full-width row below the app bar and disable `exitUntilCollapsedScrollBehavior` for that mode.

### QA gate

**Choice:** Require `openspec/UI_POLISH_QA_CHECKLIST.md` on device before merging UI polish PRs touching record, home, or studio surfaces.

## Verification

See `openspec/UI_POLISH_QA_CHECKLIST.md` and `./gradlew :feature-recording:testDebugUnitTest :feature-studio:testDebugUnitTest`.
