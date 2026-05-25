# Change: UI polish — metadata and progress unification (Tier 2)

## Why

Recording metadata and processing progress are implemented three different ways across the app. The home list uses `MetadataPillRow` (FlowRow pills with `formatRelative` / `formatAsDuration`), the Processing Studio inlines its own pill `Surface`s with different formatters (`formatForHeader` / `formatAsHumanReadableDuration`) and different source icons (Mic vs PhoneAndroid, AudioFile vs FileOpen). Processing feedback on the home list is absent — `RecordingListItem` shows no in-row progress — while a dead `RecordingCard` stack still carries a bespoke `ProcessingIndicator` (spinner + linear bar) that nothing references. This drift causes inconsistent UX on the two highest-traffic surfaces (home list and studio header) and leaves dead code that confuses future UI work.

## What Changes

- **Unify `MetadataPillRow`**: Replace inline studio metadata pills in `ProcessingStudioScreen` with the shared `MetadataPillRow` composable (relocated to `core-ui` so both `feature-recording` and `feature-studio` can consume it without cross-feature dependency).
- **Centralize `RecordingSource` icon mapping**: Extract a single `recordingSourceIcon()` / `recordingSourceLabel()` mapping used by `MetadataPillRow`, studio skeleton placeholders, and any remaining call sites — canonical icons: `PhoneAndroid` (APP), `Keyboard`, `Widgets`, `FileOpen` (IMPORTED).
- **Unify date/duration formatting**: Adopt `MetadataPillRow`'s formatters as the canonical recording-metadata display — `Date.formatRelative()` for date pills, `Long.formatAsDuration()` for duration pills — on both home list items and studio header; remove studio-only inline formatter usage for metadata.
- **Home list compact processing indicator**: When a list item is in a pipeline status (`PENDING_TRANSCRIPTION`, `TRANSCRIBING`, `PENDING_ENHANCEMENT`, `ENHANCING`), show a compact in-row progress affordance using `MorphingTranscriptionProgress(compact = true)` or a `core-ui` equivalent extracted from `TranscriptionProgressUi.kt`, aligned with studio header semantics.
- **Remove dead `RecordingCard` stack**: Delete `RecordingCard.kt`, `RecordingCardHeader.kt`, `RecordingCardContent.kt`, `RecordingCardMenu.kt`, and associated private helpers (`ProcessingIndicator`, `RecordingCardMetadata`, etc.) after confirming zero references (currently unused; home uses `RecordingListItem` + bottom sheet).
- **Document `StatsPillRow` vs `MetadataPillRow`**: Clarify in design and specs that aggregate home stats (`StatsPillRow`) and per-recording metadata (`MetadataPillRow`) are complementary, share pill visual language, and must not be merged.

## Capabilities

### New Capabilities

_(none — extends existing UI specs)_

### Modified Capabilities

- `recording-ui`: Shared recording metadata pills across home and studio; canonical source icon mapping; removal of dead card UI; StatsPillRow / MetadataPillRow relationship.
- `list-performance`: Compact in-row processing indicator on home list items without harming scroll performance.
- `transcription`: Consistent in-UI transcription progress presentation on home list (compact) matching studio header (compact banner).

## Impact

- **Modules**: `core-ui` (relocated `MetadataPillRow`, optional shared compact progress composable, source icon helper), `feature-recording` (`HomeScreenComponents.kt`, delete `RecordingCard*`), `feature-studio` (`ProcessingStudioScreen.kt`, possibly `TranscriptionProgressUi.kt` extraction).
- **Files**: `MetadataPillRow.kt`, `ProcessingStudioScreen.kt`, `HomeScreenComponents.kt`, `RecordingCard*.kt` (delete), `StatsPillRow.kt`, `TranscriptionProgressUi.kt`, `Extensions.kt` (formatter docs only; no behavior change required).
- **Tests**: Update/add Compose tests for unified metadata on studio, compact progress on home list items, and verify `RecordingCard` removal does not break compilation.
- **Risk**: Low–medium — UI-only; module relocation requires import updates. No database, navigation, or pipeline changes.

## Non-Goals

- Redesigning `StatsPillRow` aggregate chips or changing home stats computation.
- Full-screen or expanded transcription progress panels (studio tab body — covered by sibling change `ui-polish-studio-processing-ux`).
- Changing `formatForHeader` / `formatAsHumanReadableDuration` implementations or removing them from `core-contracts` (they may remain for other surfaces).
- Playback timer, notification duration, or mini-player formatting (stay on `formatAsDuration`).
- Profile name pill on metadata row (studio currently omits; home list omits — out of scope unless already in `MetadataPillRow` API).
