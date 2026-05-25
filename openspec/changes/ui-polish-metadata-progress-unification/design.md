# Design: Metadata and progress unification

## Context

### Current state

**Home list (`RecordingListItem` in `HomeScreenComponents.kt`)**

- Renders title, play button, `MetadataPillRow`, optional summary, stuck-recovery message, and tags.
- `MetadataPillRow` lives in `feature-recording/ui/components/` and uses:
  - `Date.formatRelative()` → "Today", "Yesterday", "Jan 29"
  - `Long.formatAsDuration()` → "1:05", "1:00:00"
  - Source icons: `PhoneAndroid` (APP), `Keyboard`, `Widgets`, `FileOpen` (IMPORTED)
- **No processing indicator** when `item.status` is in the transcription pipeline — users only see aggregate processing count via `StatsPillRow` at the top.

**Processing Studio (`ProcessingStudioScreen.kt`)**

- Inlines three `Surface` pills below the title with distinct styling:
  - Date: `formatForHeader()` → "Today at 10:30 AM"
  - Duration: `formatAsHumanReadableDuration()` → "1m 5s"
  - Source: `Mic` (APP), `AudioFile` (IMPORTED) — inconsistent with home
- Uses `RoundedCornerShape(8.dp)` and per-pill container colors (`secondaryContainer`, `tertiaryContainer`, `surfaceVariant`) instead of the shared circular pill shape.

**Dead `RecordingCard` stack**

- `RecordingCard.kt` is **public** but has **zero call sites** in the repo (home migrated to `RecordingListItem` + `RecordingActionsSheet`).
- `RecordingCardContent.kt` contains `ProcessingIndicator` — a bespoke spinner + `LinearProgressIndicator` pattern distinct from `MorphingTranscriptionProgress`.
- `RecordingCardMetadata` duplicates dot-separated inline text instead of pills.

**Stats vs metadata pills**

- `StatsPillRow` (`core-ui`): aggregate LazyRow — recording count, total duration, processing count filter chip.
- `MetadataPillRow`: per-recording FlowRow — date, duration, source.
- Both use `formatAsDuration()` for duration text but differ in chip primitive (`SuggestionChip` vs `Surface` pill).

### Constraints

- `feature-recording` and `feature-studio` are sibling modules; neither may depend on the other.
- Shared UI belongs in `core-ui`; domain types (`RecordingSource`) come from `data` (already transitively available via `core-ui` → `data` or direct).
- List scroll performance is a first-class requirement (`list-performance` spec).
- `MorphingTranscriptionProgress` and status→copy mapping currently live in `feature-studio/tabs/TranscriptionProgressUi.kt` as `internal` APIs.

## Goals / Non-Goals

**Goals:**

- Single shared `MetadataPillRow` used on home list items and studio header.
- Single canonical `RecordingSource` → icon + label mapping.
- Consistent date/duration strings for recording metadata on home and studio.
- Compact processing progress visible on home list items during pipeline states, visually aligned with studio compact banner.
- Remove unused `RecordingCard*` files and helpers.
- Document when to use `StatsPillRow` vs `MetadataPillRow`.

**Non-Goals:**

- Merging `StatsPillRow` and `MetadataPillRow` into one component.
- Changing transcription worker logic, notifications, or ViewModel pipeline state.
- Studio skeleton/metadata changes owned by `ui-polish-studio-processing-ux` (this change only replaces real metadata pills once loaded).
- Profile name on metadata row, tag chips, or list item layout redesign.
- Replacing `formatForHeader` / `formatAsHumanReadableDuration` globally.

## Decisions

### D1: Relocate `MetadataPillRow` to `core-ui`

| Approach | Pros | Cons |
|----------|------|------|
| **A. Move to `core-ui/components/MetadataPillRow.kt`** (chosen) | Both features import shared component; matches `StatsPillRow` location | Requires moving string/icon imports |
| B. Duplicate in `feature-studio` | No move | Perpetuates drift |
| C. `feature-studio` depends on `feature-recording` | Reuses existing file | Violates module layering |

**Decision:** Move `MetadataPillRow` and private `MetadataPill` to `core-ui/src/main/java/dev/chirpboard/app/core/ui/components/MetadataPillRow.kt`. Update `feature-recording` import. Replace studio inline pills with `<MetadataPillRow createdAtMs durationMs source />`.

Optional API extension for studio skeleton (sibling change): accept `placeholder: Boolean` or use separate skeleton chips — out of scope unless studio skeleton already needs pill-shaped placeholders (use same dimensions).

### D2: Canonical date/duration formatters for metadata pills

| Approach | Pros | Cons |
|----------|------|------|
| **A. `formatRelative` + `formatAsDuration` everywhere metadata pills appear** (chosen) | Matches existing home UX; compact for pills; already tested | Studio loses time-of-day in date pill |
| B. `formatForHeader` + `formatAsHumanReadableDuration` everywhere | Richer studio strings | Longer pill text; inconsistent with home |
| C. Parameterized `MetadataDateStyle` enum on `MetadataPillRow` | Flexible | Over-engineering for two surfaces that should match |

**Decision:** `MetadataPillRow` SHALL always use `formatRelative()` for the date pill and `formatAsDuration()` for the duration pill. Studio removes `formatForHeader()` / `formatAsHumanReadableDuration()` calls from metadata display.

**Rationale:** Metadata pills are secondary chrome; compact relative dates match list context and studio header density. Time-of-day remains available in future detail surfaces via `formatForHeader` if needed — not on pills.

**StatsPillRow alignment:** Total duration chip continues using `formatAsDuration()` — no change.

### D3: Centralized `RecordingSource` icon and label mapping

**Decision:** Add to `core-ui` (or `core-contracts` if icon-free labels only — prefer `core-ui` for Compose `ImageVector`):

```kotlin
// core-ui — RecordingSourceUi.kt (illustrative)
fun RecordingSource.icon(): ImageVector
fun RecordingSource.labelRes(): Int  // or @Composable label()
```

| Source | Icon (canonical) | Label string |
|--------|------------------|--------------|
| APP | `Icons.Filled.PhoneAndroid` | `rec_source_app` |
| KEYBOARD | `Icons.Filled.Keyboard` | `rec_source_keyboard` |
| WIDGET | `Icons.Filled.Widgets` | `rec_source_widget` |
| IMPORTED | `Icons.Filled.FileOpen` | `rec_source_imported` |

Remove inline `when (source)` blocks from `ProcessingStudioScreen` and `MetadataPillRow`. Studio's `Mic` / `AudioFile` mappings are **replaced**, not preserved.

**Alternative considered:** Use `Mic` for APP — rejected because `MetadataPillRow` already ships `PhoneAndroid` and "In-app" label; changing would affect all existing home list items without user benefit.

### D4: Home list compact processing indicator

| Approach | Pros | Cons |
|----------|------|------|
| **A. Extract shared compact progress to `core-ui`** (chosen) | Home + studio share one implementation | Small refactor of `TranscriptionProgressUi.kt` |
| B. Duplicate slim progress row in `feature-recording` | No cross-module extraction | Drift from studio |
| C. Keep dead `ProcessingIndicator` pattern | None | Inconsistent with morphing progress UX |

**Decision:**

1. Extract from `feature-studio` into `core-ui`:
   - `TranscriptionProgressCopy`, `TranscriptionProgressKind`
   - `RecordingStatus.transcriptionProgressKind()` / `transcriptionProgressCopy()` (move to `core-ui` or `core-contracts` + string resources in `core-ui`)
   - `MorphingTranscriptionProgress(compact: Boolean, …)` — public in `core-ui`
2. Studio `TranscriptionProgressUi.kt` re-exports or delegates to `core-ui` (thin wrappers stay `internal` if desired).
3. In `RecordingListItem`, after metadata pills (or between metadata and summary):

```kotlin
item.status.transcriptionProgressCopy()?.let { copy ->
    TranscriptionProgressBanner(copy) // compact MorphingTranscriptionProgress
}
```

Show when `transcriptionProgressKind() != null` — same status set as studio header.

**Layout:** Full-width compact banner inside list item padding; no `LinearProgressIndicator` bar (deprecated with `ProcessingIndicator` removal).

**Performance:** Progress composable must not start heavy animations when off-screen — rely on LazyColumn item disposal; use existing `AnimatedVisibility` only if needed for insert/remove; avoid `animateContentSize` on list items.

### D5: Remove dead `RecordingCard` stack

**Decision:** Delete after `grep` confirms zero references:

| File | Action |
|------|--------|
| `RecordingCard.kt` | Delete |
| `RecordingCardHeader.kt` | Delete |
| `RecordingCardContent.kt` | Delete (includes `ProcessingIndicator`, `RecordingCardMetadata`, `ProfileIconBadge`, `RecordingTagsRow`, `SmallTagChip`, `RecordingErrorMessage`, `MetadataDot`) |
| `RecordingCardMenu.kt` | Delete |

**Salvage check before delete:**

- `ProfileIconBadge`, `SmallTagChip`, `RecordingTagsRow` — only used by card stack; home list has `CompactTagChip` instead. Safe to delete.
- `RecordingCardMenu` — superseded by `RecordingActionsSheet`. Safe to delete.

If any helper is needed later, recover from git history — do not keep "just in case."

### D6: `StatsPillRow` vs `MetadataPillRow` relationship

**Decision:** Document as complementary components in the same design-system tier:

| Component | Scope | Data | Interaction | Container |
|-----------|-------|------|-------------|-----------|
| `StatsPillRow` | Home screen header | Aggregates across all/filtered recordings | Processing chip filters list | `LazyRow` / `SuggestionChip` |
| `MetadataPillRow` | Per-recording surfaces | Single recording date, duration, source | Read-only | `FlowRow` / `Surface` pill |

**Shared conventions:**

- Duration text: `formatAsDuration()`
- Icon size: 18.dp
- Container color: `surfaceContainerHigh` for neutral pills
- Do not embed per-recording metadata inside `StatsPillRow` or aggregate stats inside `MetadataPillRow`

**Future (non-blocking):** Optional shared `ChirpMetadataPill` primitive extracted if visual drift reappears — not required for this change.

### D7: Visual alignment when studio adopts `MetadataPillRow`

Studio inline pills currently use colored containers per field. `MetadataPillRow` uses uniform `surfaceContainerHigh` for all pills.

**Decision:** Accept `MetadataPillRow` default styling on studio for consistency with home. Do not add a `colorVariant` parameter unless design review demands it — unified look is the goal.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Studio date pill loses time-of-day | Document in release notes; `formatForHeader` remains available for non-pill contexts |
| Moving progress UI to `core-ui` pulls studio strings into core | Move string resources to `core-ui/res` or keep strings in `core-ui` R with studio reusing |
| List item height grows with progress banner | Compact mode only; single row; test scroll with 100+ items |
| Accidental `feature-studio` → `feature-recording` dependency | Code review checklist; module graph lint |
| Deleting `RecordingCard` breaks external consumers | Repo grep shows none; module is library not published SDK |

## Migration Plan

1. Add `RecordingSourceUi` + relocated `MetadataPillRow` to `core-ui`.
2. Update `feature-recording` imports; verify home list unchanged visually.
3. Replace studio inline pills; verify header metadata matches home formats/icons.
4. Extract compact progress to `core-ui`; wire into `RecordingListItem`; update studio imports.
5. Delete `RecordingCard*` files; run full compile + androidTests.
6. No feature flag; rollback = revert PR.

## Open Questions

- **String resource ownership:** Should transcription progress strings move from `feature-studio` to `core-ui` R, or duplicate minimally? **Default:** move to `core-ui` with same string keys where possible.
- **List item progress placement:** Below metadata pills vs above summary? **Default:** below metadata, above summary/stuck message.
- **IMPORTED icon:** Confirm `FileOpen` over `AudioFile` with design — **Default:** `FileOpen` per existing `MetadataPillRow`.
