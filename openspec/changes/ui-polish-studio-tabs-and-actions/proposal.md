# Change: Studio tabs and actions polish (Tier 2)

## Why

Processing Studio Summary, Transcript, and Chat tabs share the same pager but diverge in action affordances, i18n, loading feedback, and accessibility. Summary structured-outcome actions use plain `TextButton` labels while Transcript copy actions already use icon-leading `OutlinedButton`s; chat typing state is tracked in the ViewModel but never surfaced; several user-visible strings and content descriptions remain hardcoded in English; structured-outcome LLM generation shows static text instead of the morphing progress pattern used elsewhere; and Summary tab layout differs from Transcript tab padding/container conventions. These gaps were flagged as Tier 2 in the UI audit and affect daily review workflows after transcription completes.

## What Changes

- Extract **`CopyActionButton`** and **`StudioOutlinedAction`** composables into `core-ui` — shared icon-leading outlined action buttons matching Transcript tab styling.
- Apply shared actions to **Summary tab structured outcomes** with icons: `ContentCopy` (copy), `Share` (share), `AutoAwesome` (regenerate/generate header action where applicable), `Chat` (Ask AI).
- **Wire `isTyping`** from `ProcessingStudioViewModel` / `ProcessingStudioState` through `ProcessingStudioScreen` into **`ChatTab`**, showing an assistant-side typing indicator bubble (reuse `ThinkingDots` or equivalent) while the LLM response is in flight.
- **i18n and a11y**: Move TranscriptTab hardcoded strings (`manual correction` banner, word-timing note) and ChatTab placeholder / send content description into `feature-studio/.../strings.xml`; replace Processing Studio error banner hardcoded `"Error"` content description with a string resource.
- **Summary LLM generation spinner**: Replace plain "Generating structured outcomes..." info text with **`MorphingTranscriptionProgress` compact** (or inline variant) during `structuredOutcomeSection.isGenerating`, reusing studio motion tokens.
- **Summary tab container consistency**: Align outer layout, padding, and scroll structure with TranscriptTab (Column + weighted content region or equivalent LazyColumn conventions; consistent `contentPadding` application).
- **Copy micro-feedback (optional)**: Brief icon or label confirmation on copy actions (transcript and structured outcomes) — e.g., crossfade to check icon or localized snackbar via existing message flow; unify `onStructuredOutcomeCopied` to use `rec_copied_to_clipboard` string resource.

## Capabilities

### New Capabilities

_(none — all changes extend existing UI/LLM specs and core-ui components)_

### Modified Capabilities

- `recording-ui`: Shared studio action buttons, Summary/Transcript/Chat tab polish, typing indicator, i18n strings, error banner accessibility, Summary container parity, optional copy confirmation.
- `llm-processing`: In-tab structured-outcome generation progress presentation and chat typing affordance during LLM response.
- `ui-animations`: Reuse morphing progress and thinking/typing animation patterns for Summary generation and Chat typing states.

## Impact

- **Modules**: `core-ui` (new action composables), `feature-studio` (SummaryTab, TranscriptTab, ChatTab, ProcessingStudioScreen, ProcessingStudioViewModel, strings.xml).
- **Files**: `SummaryTab.kt`, `ChatTab.kt`, `TranscriptTab.kt`, `ProcessingStudioScreen.kt`, `ProcessingStudioViewModel.kt`, `feature-studio/src/main/res/values/strings.xml`; possible new `core-ui/.../StudioActionButtons.kt` (or similar).
- **Tests**: Extend `SummaryTabTest`, add ChatTab typing indicator test, string-resource coverage for migrated hardcoded copy; optional copy-feedback test.
- **Risk**: Low — UI-only; no database, pipeline, or navigation changes. `core-ui` gains two small public composables consumed by feature-studio.

## Non-Goals

- Redesigning Chat message layout, markdown rendering, or LLM chat workflow logic beyond `isTyping` wiring.
- Changing structured-outcome extraction API, persistence, or generation triggers.
- Migrating TranscriptTab copy actions to new components in this change (TranscriptTab is the reference implementation; optional follow-up refactor).
- Global snackbar/toast system redesign — reuse existing `viewModel.message` + `SnackbarHost`.
- Processing Studio header, player slot, skeleton shell, or pager prefetch (covered by `ui-polish-studio-processing-ux`).
