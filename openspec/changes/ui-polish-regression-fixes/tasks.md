# Tasks: UI polish regression fixes

## 1. Record Done handoff

- [x] 1.1 Restore immediate `onRecordingComplete(activeRecordingId)` on Done with `hasNavigatedToComplete` guard in `RecordScreen.kt`
- [x] 1.2 Keep `LaunchedEffect(lastCompletedRecordingId)` as fallback; clear ID after navigate

## 2. Studio single progress surface

- [x] 2.1 `TranscriptTab`: skeleton only during processing — remove tab-level `TranscriptionProgressPanel`

## 3. Home filter and empty states

- [x] 3.1 Fix `showEmptyState` to use total recording count, not filtered list
- [x] 3.2 Add filtered-empty inline message + clear filter button
- [x] 3.3 Processing pill: selected state, dismiss chip, no-op when zero processing items (`HomeViewModel.onProcessingClick`)

## 4. Home search layout

- [x] 4.1 Move search field below app bar; disable collapse while search active

## 5. Documentation

- [x] 5.1 Correct nav-shell and studio-processing OpenSpec designs/specs/tasks
- [x] 5.2 Add `openspec/UI_POLISH_QA_CHECKLIST.md`

## 6. Verification

- [x] 6.1 `./gradlew :app:assembleDebug :feature-recording:testDebugUnitTest :feature-studio:testDebugUnitTest`
- [ ] 6.2 Device walkthrough per QA checklist (Record→Done, filters, search, studio progress)
