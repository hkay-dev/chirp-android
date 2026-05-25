# Tasks: Studio tabs and actions polish

## 1. core-ui shared action components

- [x] 1.1 Add `StudioActionButtons.kt` in `core-ui` with `StudioOutlinedAction` (icon, label, enabled, optional icon CD) and `CopyActionButton` wrapper defaulting to `Icons.Outlined.ContentCopy`
- [x] 1.2 Match Transcript reference styling: `OutlinedButton`, 18.dp icon, 8.dp spacer, Material typography
- [x] 1.3 Verify `:core-ui:compileDebugKotlin` succeeds with no new module dependencies

## 2. String resources and ViewModel i18n

- [x] 2.1 Add strings to `feature-studio/.../values/strings.xml`: `rec_transcript_manual_correction_banner`, `rec_transcript_word_timing_unavailable`, `rec_chat_placeholder`, `rec_chat_send`, `rec_processing_error`, `rec_structured_generating_title`, `rec_structured_generating_subtitle`
- [ ] 2.2 Replace hardcoded copy in `TranscriptTab.kt` (manual correction banner, word-timing note) with string resources
- [x] 2.3 Replace hardcoded placeholder and send CD in `ChatTab.kt` with string resources
- [x] 2.4 Replace hardcoded error icon CD in `ProcessingStudioScreen.kt` with `rec_processing_error`
- [x] 2.5 Fix `onStructuredOutcomeCopied()` to use `R.string.rec_copied_to_clipboard` instead of hardcoded English

## 3. Summary tab actions and layout

- [x] 3.1 Replace structured outcome item `TextButton` actions in `SummaryTab.kt` with `StudioOutlinedAction` / `CopyActionButton` (icons: ContentCopy, Share, Chat)
- [x] 3.2 Refactor `SummaryTab` outer layout for Transcript parity: single root padding owner, 12.dp section spacing, weighted scroll region
- [x] 3.3 Optionally upgrade Generate/Regenerate header to `StudioOutlinedAction` with `AutoAwesome` if layout permits

## 4. Structured outcome generation progress

- [x] 4.1 Extend `MorphingTranscriptionProgress` (or wrap) to accept optional `leadingIcon` and generic `TranscriptionProgressCopy` for LLM waits
- [x] 4.2 Replace static `StructuredOutcomeInfo` generating branches with compact morphing progress + `AnimatedVisibility`
- [x] 4.3 Wire generating title/subtitle from new string resources; use `AutoAwesome` leading icon for structured outcomes

## 5. Chat typing indicator

- [x] 5.1 Add `isTyping: Boolean` parameter to `ChatTab`
- [x] 5.2 Implement `AssistantTypingBubble` using `ThinkingDots` and assistant bubble styling (left-aligned, matching `ChatMessageBubble` radii)
- [x] 5.3 Pass `state.isTyping` from `ProcessingStudioScreen` to `ChatTab`
- [x] 5.4 Animate typing bubble enter/exit with studio reveal/hide transitions

## 6. Copy micro-feedback (optional)

- [ ] 6.1 Ensure transcript and structured-outcome copy both trigger localized snackbar via existing message flow
- [ ] 6.2 (Optional) Add brief check-icon crossfade on `CopyActionButton` after successful copy (200ms, revert ≤1.5s)

## 7. Verification

- [ ] 7.1 Extend `SummaryTabTest`: structured outcome actions render outlined buttons with icons; generating state shows progress UI
- [ ] 7.2 Add Compose test for `ChatTab` typing indicator when `isTyping = true`
- [ ] 7.3 Manual smoke: copy/share/ask AI on structured outcomes, chat send + typing bubble, error banner TalkBack label, Summary/Transcript padding alignment
- [ ] 7.4 Run `./gradlew :core-ui:compileDebugKotlin :feature-studio:compileDebugKotlin :feature-studio:connectedDebugAndroidTest` (or project CI equivalent)
