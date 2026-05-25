# Tasks: Studio processing UX polish

## 1. Loading shell and ID gating

- [ ] 1.1 Split `ProcessingStudioScreen` entry: full-screen `LoadingState` only when `screenRecordingId == null` (invalid/missing ID); remove early return for valid UUID + `isLoading`
- [ ] 1.2 Add internal `ProcessingStudioSkeleton` composable: real top bar + back, tab row, title placeholder, three metadata chip placeholders
- [ ] 1.3 Render skeleton when valid ID and (`state.isLoading` or essential metadata not yet populated); transition to real metadata as flows emit

## 2. ViewModel handoff timing

- [ ] 2.1 Add `playerRevealReady: Boolean` to `ProcessingStudioState` (default `false`); reset on each `loadRecording`
- [ ] 2.2 Replace `ChirpMotion.NAV_TRANSITION_MS` with `ChirpMotion.RECORD_HANDOFF_MS` in `scheduleDeferredStudioPlayback`
- [ ] 2.3 Launch handoff coroutine: after `RECORD_HANDOFF_MS`, set `playerRevealReady = true` if recording ID unchanged; gate `showPlayer` in screen on this flag

## 3. Layout animation consolidation

- [ ] 3.1 Remove `animateContentSize` from `StudioProcessingHeader`; retain single modifier on studio root column in `ProcessingStudioScreen`
- [ ] 3.2 Add player slot wrapper with conditional `heightIn(min = …)` when progress or player visible; tune min height against `RecordingFullPlayer`
- [ ] 3.3 Verify pager no longer jumps when progress banner swaps to player

## 4. HorizontalPager stability

- [ ] 4.1 Set `beyondViewportPageCount = 1` on studio `HorizontalPager`
- [ ] 4.2 Manually verify first switch to Summary and Chat tabs shows no flash

## 5. Transcript tab processing fallback

- [ ] 5.1 Add processing branch in `TranscriptTab`: when `isProcessing && !isEditingTranscript`, show `TranscriptionProgressPanel` if copy/kind available
- [ ] 5.2 Add skeleton line fallback when processing expected but kind/copy not yet available
- [ ] 5.3 Replace `animateFloatAsState` + `graphicsLayer { alpha }` on transcript chrome with `AnimatedVisibility` using `progressEnterTransition` / `progressExitTransition`

## 6. Progress UI phase icons

- [ ] 6.1 Pass `TranscriptionProgressKind` into `MorphingTranscriptionProgress` from header, panel, and banner call sites
- [ ] 6.2 Implement phase-specific leading icons (Finalizing, Transcribing, Enhancing) with `AnimatedContent` on kind change
- [ ] 6.3 Confirm compact header and expanded tab panel share consistent icon semantics

## 7. Verification

- [ ] 7.1 Add Compose test: valid ID + `isLoading` shows skeleton tabs/top bar (not full-screen spinner)
- [ ] 7.2 Add Compose test: `TranscriptTab` with processing kind shows non-empty body (panel or skeleton)
- [ ] 7.3 Manual smoke: record → studio handoff, transcribing/enhancing phases, tab switches, player appears after ~480 ms aligned with playback prep
- [ ] 7.4 Run `./gradlew :feature-studio:compileDebugKotlin :feature-studio:connectedDebugAndroidTest` (or project CI equivalent)
