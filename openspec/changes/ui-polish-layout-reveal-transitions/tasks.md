# Tasks: Layout reveal transitions

## 1. Core motion

- [x] 1.1 Add `LayoutReveal.kt` (`PushDownReveal`, `animatePushDownLayout`)
- [x] 1.2 Add `ChirpMotion.miniPlayerRevealTransition` / `miniPlayerHideTransition`

## 2. P0 — Studio & home

- [x] 2.1 `ProcessingStudioScreen`: recovery + error blocks → `PushDownReveal`
- [x] 2.2 `HomeScreen`: lazy header items → `PushDownReveal`
- [x] 2.3 `RecordingListItem`: `animatePushDownLayout` + per-block `PushDownReveal`
- [x] 2.4 `TranscriptTab`: body `AnimatedContent`; chrome → `PushDownReveal`
- [x] 2.5 `KeyboardUI`: mode controls → `PushDownReveal`; column size spring

## 3. P1 — Polish

- [x] 3.1 `HomeScreen`: FAB quick starts + empty/list `animatePushDownLayout`
- [x] 3.2 `ProcessingStudioScreen`: title metadata `AnimatedContent`
- [x] 3.3 `RecordingFullPlayer`: notice banner → `PushDownReveal`
- [x] 3.4 `AppNavigation`: mini player ChirpMotion tokens + `layoutSizeSpring`
- [x] 3.5 `VoiceRecognitionDialog`: content column `animatePushDownLayout`
- [x] 3.6 `SummaryTab`: structured outcome `AnimatedContent`
- [x] 3.7 `RecordScreen`: profile badge → `PushDownReveal`

## 4. P2 — Settings & lists

- [x] 4.1 `LlmSettingsScreen`: `PushDownReveal` for enabled sections
- [x] 4.2 `TranscriptionSettingsScreen`: download progress → `PushDownReveal`
- [x] 4.3 `ObsidianSettingsScreen`: auto-export card → `PushDownReveal`
- [x] 4.4 `AudioSettingsScreen`: manual device block → `PushDownReveal`
- [x] 4.5 Profile/tag/replacement list screens: `animatePushDownLayout` on containers

## 5. Documentation

- [x] 5.1 Spec deltas under `specs/ui-animations`, `recording-ui`, `keyboard-ux`
- [x] 5.2 Extend `openspec/UI_POLISH_QA_CHECKLIST.md`

## 6. Verification

- [x] 6.1 `./gradlew :app:assembleDebug :feature-recording:testDebugUnitTest :feature-studio:testDebugUnitTest :core-ui:compileDebugKotlin`
- [ ] 6.2 Device walkthrough per QA checklist
- [x] 6.3 Install debug APK on connected device
