# Change: Studio processing UX polish (Tier 1)

## Why

The Processing Studio screen currently replaces the entire UI with a full-screen spinner while recording data loads, leaves the transcript tab blank during active transcription, stacks competing `animateContentSize` modifiers that cause layout jank when the player and progress header swap, flashes adjacent pager pages on tab switches, fades transcript content with alpha-only animation (hurting accessibility and touch handling), shows a generic spinner for all transcription phases, and defers playback sync on a mismatched timer (`NAV_TRANSITION_MS` instead of `RECORD_HANDOFF_MS`). These issues were flagged as Tier 1 in the UI audit and directly affect the post-recording handoff—the highest-traffic path in the app.

## What Changes

- Replace the early `LoadingState()` return on valid recording IDs with a **skeleton shell**: top app bar, tab row, and metadata chip placeholders remain visible while DB flows emit.
- Reserve **full-screen `LoadingState`** only for invalid or missing recording IDs (unparseable UUID, empty, or sentinel `-1`).
- Add a **processing fallback** in `TranscriptTab` when `transcriptionProgressKind()` is non-null: skeleton transcript lines and/or centered `TranscriptionProgressPanel` so the tab body is never empty.
- Consolidate **layout size animation** to a single owner on the studio root column; give the player slot a **fixed minimum height** so progress-to-player transitions do not collapse and re-expand the pager.
- Set `HorizontalPager` **`beyondViewportPageCount = 1`** to pre-compose adjacent tabs and eliminate first-switch flash.
- Replace transcript body **alpha-only `graphicsLayer` fades** with **`AnimatedVisibility`** enter/exit transitions aligned to `ChirpMotion` studio tokens.
- Add **phase-specific leading icons** in `MorphingTranscriptionProgress` for Finalizing, Transcribing, and Enhancing (spinner remains secondary or combined per phase).
- Wire **`ChirpMotion.RECORD_HANDOFF_MS`** for deferred studio playback preparation and align in-screen player reveal timing with the same constant so header layout and playback sync finish together.

## Capabilities

### New Capabilities

_(none — all changes extend existing UI/transcription specs)_

### Modified Capabilities

- `recording-ui`: Studio detail loading shell, skeleton metadata, player slot stability, and handoff-aligned player reveal.
- `ui-animations`: Single layout-size animation owner for studio, `AnimatedVisibility` for transcript chrome, pager prefetch, phase morph progress visuals.
- `transcription`: In-UI transcription progress presentation (phase icons, transcript-tab processing fallback, progress copy during pipeline states).

## Impact

- **Modules**: `feature-studio` (primary), `core-ui` (`ChirpMotion` constant already defined; no new deps expected).
- **Files**: `ProcessingStudioScreen.kt`, `StudioProcessingHeader.kt`, `TranscriptTab.kt`, `TranscriptionProgressUi.kt`, `ProcessingStudioViewModel.kt`; possible string additions in `feature-studio/.../values/strings.xml`.
- **Tests**: Extend or add Compose tests in `feature-studio` for skeleton visibility, processing fallback, and tab pager behavior.
- **Risk**: Low — UI-only; no database, navigation graph, or transcription pipeline changes.

## Non-Goals

- Redesigning Summary or Chat tabs beyond pager prefetch benefit.
- Changing transcription worker logic, recovery flows, or notification progress.
- Introducing a global shimmer/design-system component (local placeholders only unless already present in `core-ui`).
- Altering `RecordingFullPlayer` internals or playback controller API surface beyond defer timing.
