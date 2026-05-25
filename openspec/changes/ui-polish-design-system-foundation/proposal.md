# Change: UI polish — design system foundation

## Why

Settings and leaf screens duplicate scaffold, list-item, loading, and theme code across six modules. Typography and shapes exist in `core-ui` but are not wired into `MaterialTheme`; accessibility strings split between `desc_back` and `desc_navigate_back`; icon families mix `Icons.Default`, `Icons.Filled`, and `Icons.Rounded`. This inconsistency increases maintenance cost and blocks the broader UI polish pass from landing safely.

## What Changes

- Add `ChirpSettingsDetailScaffold` — large-title collapsing scaffold for leaf settings screens — alongside existing `ChirpLeafScaffold` and `ChirpSettingsHubScaffold` in `core-ui/ChirpScaffolds.kt`.
- Extract `SettingsDropdownListItem` to `core-ui` (currently duplicated in Audio, Keyboard, and Processing Mode pickers).
- Add `ChirpTypography` and wire `ChirpShapes` into `ChirpTheme` (`Theme.kt`) so `MaterialTheme.typography` and `MaterialTheme.shapes` reflect app tokens.
- Unify `desc_back` / `desc_navigate_back` to a single core-ui string resource.
- Extract `SettingsConnectionStatusRow` and `SettingsActionButtonRow` from LLM, Obsidian, and Transcription settings into reusable `core-ui` components.
- Add `ChirpInlineLoadingIcon` — Crossfade between spinner and leading icon for action buttons.
- Collapse `KeyboardTheme` to a type alias / thin wrapper over `ChirpTheme`.
- Migrate `AudioSettingsScreen`, `KeyboardSettingsScreen`, `LlmSettingsScreen`, `TranscriptionSettingsScreen`, `ObsidianSettingsScreen`, and `DevMenuScreen` to shared scaffolds.
- Standardize settings navigation icons to `Icons.Rounded` family; use `Icons.Rounded.AutoAwesome` for LLM surfaces.
- Document button hierarchy (`Button` / `OutlinedButton` / `TextButton`) in design doc and AGENTS/design-system guidance.

## Capabilities

### New Capabilities

- `design-system`: Shared Compose scaffolds, settings list patterns, typography/shape tokens, loading affordances, icon conventions, and button hierarchy for Chirp UI.

### Modified Capabilities

- `app-structure`: Leaf settings screens SHALL use shared `core-ui` scaffolds instead of bespoke `Scaffold` + `LargeTopAppBar` copies.
- `keyboard-ux`: Keyboard IME Compose tree SHALL use `ChirpTheme` (via `KeyboardTheme` alias) so typography and shapes match the main app.

## Impact

- **Modules**: `core-ui`, `app` (Audio/Keyboard settings, DevMenu), `feature-keyboard` (theme alias), `feature-llm`, `feature-transcription`, `feature-obsidian`.
- **APIs**: New public composables in `core-ui`; `KeyboardTheme` becomes alias — no behavioral change for keyboard users.
- **Strings**: Consolidate back-navigation content description; remove duplicate `desc_back` from `app` and `shared_ui_strings.xml` where superseded.
- **Tests**: Update compose/screenshot tests referencing old scaffold patterns or `desc_back` resource IDs.
- **Docs**: Button hierarchy guidance added to design artifact (no standalone README unless requested at archive).

## Non-Goals

- Redesigning Settings hub layout (`SettingsScreen` already uses `ChirpSettingsHubScaffold`).
- Migrating non-settings leaf screens (Profile editor, Processing Studio, Word Replacements) — follow-up changes.
- Changing settings business logic, ViewModels, or navigation graph structure.
- Introducing a new icon font or custom typeface.
