# Design: Studio tabs and actions polish

## Context

Processing Studio exposes three pager tabs — Transcript, Summary, and Chat — after the Tier 1 processing UX work (`ui-polish-studio-processing-ux`). Tab bodies still diverge:

| Area | Current state | Gap |
|------|---------------|-----|
| Structured outcome actions | `TextButton` text labels (Copy, Share, Ask AI) | No icons; inconsistent with Transcript `OutlinedButton` + icon pattern |
| Chat typing | `ProcessingStudioState.isTyping` set in `onSendChatMessage` | `ChatTab` never receives flag; no assistant typing bubble |
| i18n | TranscriptTab lines 112, 237; ChatTab lines 79, 95; error Icon line 491 | Hardcoded English; a11y violation for error icon |
| Summary generation | `StructuredOutcomeInfo` static text when `isGenerating` | No morphing progress; feels disconnected from transcription progress UX |
| Summary layout | `LazyColumn(contentPadding = …)` root | TranscriptTab uses `Column` + outer `padding(contentPadding)` + weighted `Box`; visual rhythm differs |
| Copy feedback | Transcript uses localized snackbar; structured outcome uses hardcoded `"Copied to clipboard"` | Inconsistent; no inline micro-feedback |

**Constraints**

- Jetpack Compose + Material 3; motion tokens in `ChirpMotion`; `ThinkingDots` and `MorphingTranscriptionProgress` already exist.
- New public API in `core-ui` must stay minimal — two composables, no new module dependencies.
- Tier 2 scope — prefer wiring existing state over new ViewModel fields.
- `feature-studio` already depends on `core-ui`; no circular deps.

**Reference implementation**

`TranscriptCopyActions` in `TranscriptTab.kt` is the visual source of truth:

```kotlin
OutlinedButton(onClick = …) {
    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(R.string.rec_copy_original))
}
```

## Goals / Non-Goals

**Goals:**

- Reusable `core-ui` action buttons matching Transcript styling.
- Summary structured outcomes use icon-leading outlined actions (Copy, Share, Ask AI).
- Chat shows assistant typing bubble while `isTyping == true`.
- All user-visible studio tab strings and error icon CD in string resources.
- Structured-outcome generation shows compact morphing progress inline.
- Summary tab outer layout/padding aligns with Transcript tab conventions.
- Optional brief copy confirmation (localized snackbar at minimum; inline icon crossfade if low-cost).

**Non-Goals:**

- Refactoring TranscriptTab to use new composables (optional follow-up).
- Chat streaming tokens, markdown, or multi-turn UX redesign.
- New LLM endpoints or structured-outcome schema changes.
- Cross-app action button migration (settings, list screens).
- Replacing snackbar-based copy feedback with a new global system.

## Decisions

### D1: Shared action composables in core-ui

| Approach | Pros | Cons |
|----------|------|------|
| **A. Two composables: `StudioOutlinedAction` + thin `CopyActionButton` wrapper** (chosen) | Matches Transcript; `CopyActionButton` encodes copy icon default | Two public APIs |
| B. Single generic `StudioOutlinedAction` only | Smaller API | Call sites repeat icon/spacing |
| C. Keep inline OutlinedButton per screen | No core-ui change | Duplication persists |

**Decision:** Add `core-ui/src/main/java/dev/chirpboard/app/core/ui/components/StudioActionButtons.kt`:

```kotlin
@Composable
fun StudioOutlinedAction(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconContentDescription: String? = null, // null when decorative (label present)
)

@Composable
fun CopyActionButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = StudioOutlinedAction(
    onClick = onClick,
    icon = Icons.Outlined.ContentCopy,
    label = label,
    modifier = modifier,
    enabled = enabled,
)
```

**Visual contract:** `OutlinedButton`, 18.dp icon, 8.dp icon–label gap, `MaterialTheme` typography — identical to Transcript copy buttons.

**Summary structured outcome mapping:**

| Action | Icon | Label resource |
|--------|------|----------------|
| Copy | `Icons.Outlined.ContentCopy` | `CoreR.string.rec_copy` |
| Share | `Icons.Default.Share` (or `Icons.Outlined.Share`) | `CoreR.string.rec_share` |
| Ask AI | `Icons.Default.Chat` (or `Icons.Outlined.Chat`) | `R.string.rec_ask_ai` |

Generate/Regenerate header remains `TextButton` or upgrades to `StudioOutlinedAction` with `Icons.Filled.AutoAwesome` — implementer choice; spec requires icon on per-item actions only.

### D2: Chat typing indicator wiring

**Decision:** Thread existing state — no new ViewModel fields.

1. `ProcessingStudioScreen` passes `isTyping = state.isTyping` to `ChatTab`.
2. `ChatTab` adds parameter `isTyping: Boolean = false`.
3. When `isTyping`, render `ChatTypingIndicatorBubble` at list head (reverse layout → appears below latest messages visually at bottom of history):

```kotlin
if (isTyping) {
    item(key = "typing_indicator") {
        AssistantTypingBubble() // Surface + ThinkingDots, left-aligned
    }
}
```

Reuse `ThinkingDots` from `core-ui` inside a `Surface` matching assistant bubble colors (`surfaceVariant`, same corner radii as `ChatMessageBubble`).

**Alternative considered:** Pulsing "Assistant is typing…" text — rejected; dots match existing processing affordance and stay compact.

**Edge case:** If user sends while prior response pending, `isTyping` remains true until `completeStudioChatExchange` returns — existing ViewModel behavior; no change.

### D3: i18n string migration

**Decision:** Add to `feature-studio/src/main/res/values/strings.xml`:

| Key | English value | Replaces |
|-----|---------------|----------|
| `rec_transcript_manual_correction_banner` | Showing your saved manual correction. | TranscriptTab line 112 |
| `rec_transcript_word_timing_unavailable` | Word-level timing isn't available for this transcript. | TranscriptTab line 237 |
| `rec_chat_placeholder` | Ask about this recording… | ChatTab placeholder |
| `rec_chat_send` | Send message | ChatTab IconButton CD |
| `rec_processing_error` | Error | ProcessingStudioScreen error Icon CD |

Use ellipsis character `…` in placeholder for consistency with Material patterns.

**ViewModel fix:** `onStructuredOutcomeCopied()` SHALL use `context.getString(R.string.rec_copied_to_clipboard)` (already exists) instead of hardcoded English.

### D4: Summary structured-outcome generation progress

| Approach | Pros | Cons |
|----------|------|------|
| **A. Compact `MorphingTranscriptionProgress` inline** (chosen) | Visual parity with transcription UX | Needs LLM-specific copy strings |
| B. `CircularProgressIndicator` + text row | Simpler | Breaks morph pattern |
| C. Reuse transcription phase icons | Single component | Wrong semantics (not transcribing) |

**Decision:**

- Add strings: `rec_structured_generating_title`, `rec_structured_generating_subtitle` (or reuse `rec_structured_generating` split into title/subtitle pair for `TranscriptionProgressCopy`).
- When `state.isGenerating && !state.hasReadySnapshot`, show compact morph progress instead of `StructuredOutcomeInfo` text-only branch.
- When regenerating with existing snapshot (`isGenerating && hasReadySnapshot`), show compact banner above groups (same component, `AnimatedVisibility` enter/exit).
- Pass `compact = true` to `MorphingTranscriptionProgress`; use neutral LLM icon (`Icons.Filled.AutoAwesome`) as leading icon — extend component with optional `leadingIcon` parameter OR wrap with Row if extension is out of scope for Tier 2.

**Preferred extension:** Add optional `leadingIcon: ImageVector? = null` to `MorphingTranscriptionProgress` for non-transcription reuse (structured outcomes, future LLM waits). When null, keep current spinner-only behavior for transcription phases.

### D5: Summary tab container parity with TranscriptTab

**Decision:** Align without rewriting TranscriptTab.

| TranscriptTab pattern | SummaryTab target |
|-----------------------|-------------------|
| Root `Column(modifier.fillMaxSize().padding(contentPadding))` | Same outer structure |
| `verticalArrangement = spacedBy(12.dp)` | Match 12.dp (currently 16.dp internal — tune to 12.dp between major sections) |
| Weighted scroll region for main body | Keep `LazyColumn` inside `Box(Modifier.weight(1f))` if switching to Column root, OR keep LazyColumn as root but apply `contentPadding` via outer modifier pattern equivalent to Transcript |

**Concrete approach:** Refactor `SummaryTab` to:

```kotlin
Column(Modifier.fillMaxSize().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // summary body + structured outcomes
    }
}
```

Remove duplicate `contentPadding` on LazyColumn when outer Column owns padding — matches Transcript's single padding owner.

Summary body `Surface` styling: retain `surfaceVariant` alpha card; structured outcome section keeps `tonalElevation = 2.dp` — no visual redesign, only layout rhythm.

### D6: Copy micro-feedback (optional)

| Approach | Pros | Cons |
|----------|------|------|
| **A. Localized snackbar only** (minimum) | Already wired; zero new UI | Easy to miss |
| **B. Snackbar + optional icon crossfade on button** (stretch) | Clear affordance | Requires local button state |
| C. Haptic only | Subtle | Not accessible |

**Decision:** **Required:** unify structured-outcome copy to localized snackbar via `rec_copied_to_clipboard` (same as transcript).

**Optional (implement if ≤ ~30 lines):** `CopyActionButton` accepts optional `showConfirmation: Boolean` driven by parent remembering last-click timestamp; crossfade icon to `Icons.Filled.Check` for 1.5s using `ChirpMotion` quick duration (200ms). If scope creeps, ship snackbar-only and document icon crossfade as follow-up.

### D7: TranscriptTab refactor scope

**Decision:** Do **not** migrate `TranscriptCopyActions` to shared composables in this change. SummaryTab adoption validates API; Transcript migration is a one-line follow-up once components stabilize.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| `MorphingTranscriptionProgress` transcription coupling | Optional `leadingIcon` + generic copy params; transcription call sites unchanged when icon null |
| Chat typing bubble layout jump when `isTyping` toggles | `AnimatedVisibility` on typing item; reverse LazyColumn already animates inserts lightly |
| Summary Column + LazyColumn nesting performance | Single child LazyColumn with weight — standard pattern; only two sections |
| core-ui API surface creep | Only two composables; no theme changes |
| Optional copy crossfade state per button | Limit to Copy actions only; use `remember { mutableStateOf }` at item level |

## Migration Plan

1. Land `core-ui` composables first; compile feature-studio against them.
2. Update SummaryTab actions and generation UI; wire ChatTab `isTyping`.
3. Migrate strings; run `./gradlew :feature-studio:compileDebugKotlin :core-ui:compileDebugKotlin`.
4. Extend androidTests (`SummaryTabTest`, new `ChatTabTest` or screen-level test).
5. Manual smoke: generate structured outcomes, copy/share/ask AI, send chat message, verify typing bubble and snackbar.
6. Rollback: single PR revert; no data migration.

## Open Questions

- Should Generate/Regenerate header action also become `StudioOutlinedAction` with `AutoAwesome`? **Default:** yes if layout fits; otherwise keep TextButton.
- Exact `MorphingTranscriptionProgress` extension vs wrapper Row for LLM icon — **Default:** optional `leadingIcon` parameter to avoid duplicate progress shell.
- Implement inline copy check crossfade in this change or defer? **Default:** snackbar required; crossfade optional if time permits.
