# Change: UI polish — layout reveal transitions

## Why

After adding `ChirpMotion.pushDownRevealTransition` for Home search and Studio header/player, a full-app audit found ~15 surfaces that still show or hide vertical chrome with bare `if` blocks, LazyColumn item pops, or slide-only `AnimatedVisibility`. Users perceive these as layout jumps — especially during transcription (list row growth), studio recovery/error blocks, transcript tab body swaps, and keyboard mode controls.

## What Changes

### Core motion (`core-ui`)

- Add `PushDownReveal` composable and `Modifier.animatePushDownLayout()` wrapping `animateContentSize(layoutSizeSpring)`.
- Add `ChirpMotion.miniPlayerRevealTransition` / `miniPlayerHideTransition` for nav shell consistency.

### P0 — High-traffic jumps

- **Processing Studio**: `TranscriptionRecoverySection` and error `Surface` → `PushDownReveal`.
- **Home list header**: recovery banner, stats/filter/stuck rows, search/filter-empty labels → `PushDownReveal`.
- **Home list rows**: progress banner, summary, stuck text, tags → `animatePushDownLayout` + `PushDownReveal` per block.
- **Transcript tab**: copy chrome → `PushDownReveal`; body skeleton/transcript/editor → `AnimatedContent` + `layoutSizeSpring`.
- **Keyboard IME**: `ModeControlsRow` → `PushDownReveal`; content column → `animatePushDownLayout`.

### P1 — Polish / consistency

- **Home**: FAB quick-start stack → `PushDownReveal`; empty ↔ list content → `animatePushDownLayout`.
- **Studio**: title edit ↔ display metadata → `AnimatedContent` + size spring.
- **Playback**: `RecordingFullPlayer` alternate notice → `PushDownReveal`.
- **Nav shell**: mini player uses `ChirpMotion.miniPlayer*` tokens + `layoutSizeSpring`.
- **Voice dialog**: content column → `animatePushDownLayout`.
- **Summary tab**: structured outcome section → `AnimatedContent` on section state.

### P2 — Settings & lower traffic

- **LLM / Transcription / Obsidian / Audio settings**: replace default `expandVertically()` with `PushDownReveal` on conditional sections.
- **Profile / word replacements / tag screens**: `animatePushDownLayout` on empty ↔ list containers.

### Documentation & QA

- New `ui-animations` spec requirements for push-down chrome vs in-place crossfade.
- Extend `openspec/UI_POLISH_QA_CHECKLIST.md` with layout-reveal smoke items.

## Capabilities

### Modified Capabilities

- `ui-animations`: Push-down reveal tokens, `PushDownReveal` helper, mini-player tokens, transition selection guide.
- `recording-ui`: Home list chrome, list item growth, studio recovery/error, title edit, FAB quick starts.
- `transcription`: Transcript tab body transitions (studio).
- `keyboard-ux`: Mode controls reveal.
- `list-performance`: Animated row growth during pipeline states.

## Impact

| Module | Files |
|--------|-------|
| core-ui | `ChirpMotion.kt`, `LayoutReveal.kt` |
| core-playback | `RecordingFullPlayer.kt`, `RecordingMiniPlayerBar.kt` |
| app | `AppNavigation.kt`, `VoiceRecognitionDialog.kt` |
| feature-recording | `HomeScreen.kt`, `HomeScreenComponents.kt`, `RecordScreen.kt` |
| feature-studio | `ProcessingStudioScreen.kt`, `TranscriptTab.kt`, `SummaryTab.kt` |
| feature-keyboard | `KeyboardUI.kt` |
| feature-llm | `LlmSettingsScreen.kt` |
| feature-transcription | `TranscriptionSettingsScreen.kt` |
| feature-obsidian | `ObsidianSettingsScreen.kt` |
| app settings | `AudioSettingsScreen.kt` |
| profile/tags | `ProfileListScreen.kt`, `WordReplacementsScreen.kt`, `TagManagementScreen.kt` |

**Risk:** Low — Compose-only. LazyColumn animated items may still shift scroll offset slightly; mitigated by single animated header block where possible.

## Non-Goals

- Rewriting nav transition curves between destinations.
- Animating modal dialog scale/fade (keep `AnimatedAlertDialog`).
- Per-row stagger delays in bottom sheets.
