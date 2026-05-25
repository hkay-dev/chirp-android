# UI polish manual QA checklist

Run on a physical device **before merging** any PR that touches home list, record flow, Processing Studio, or nav shell composables (`AppNavigation`, `RecordScreen`, `HomeScreen`, `ProcessingStudioScreen`).

## Record → Done → Studio

- [ ] Start recording from home FAB; speak for a few seconds; tap **Done**
- [ ] App navigates to Processing Studio **immediately** (no lingering on Record while finalize runs)
- [ ] Studio shows shell (title area, tabs, header progress) with finalizing / stitching status visible
- [ ] Press back once → returns to Home (not duplicate Studio entries on back stack)
- [ ] Repeat Done flow twice in a row — still exactly one navigation per stop

## Processing Studio progress (single surface)

- [ ] During finalizing / transcribing / enhancing, **one** morphing progress region is visible (header compact banner)
- [ ] Transcript tab shows skeleton lines only — **not** a second full progress panel with duplicate copy/spinner
- [ ] When processing completes, transcript content (or empty-completed state) replaces skeleton without layout jump

## Home list filters and empty states

- [ ] With recordings present, tap processing stat pill when count is **0** — list and stats **stay visible**; filter does not activate
- [ ] With processing recordings present, tap processing pill — list filters to processing items; pill shows selected state; dismissible **Processing** chip appears
- [ ] Tap processing pill again (or chip dismiss) — filter clears; full list returns
- [ ] Activate processing filter when no items match — stats row remains; inline “no match” message and **Clear filter** appear (not first-run empty state)
- [ ] First-run empty state appears **only** when there are zero recordings total and no active search

## Home search

- [ ] Tap search icon — search field is fully visible (not clipped by collapsing top app bar)
- [ ] Type query — results filter; results count label updates
- [ ] Clear query / close search — field dismisses cleanly; list restores

## Nav shell (when touched)

- [ ] Start playback from list — mini player slides in; content height adjusts smoothly
- [ ] Stop playback — mini player exits without jank
- [ ] Share audio into app — scrim overlay appears; Home/NavHost stays mounted underneath

## Regression smoke (quick)

- [ ] `./gradlew :app:assembleDebug :feature-recording:testDebugUnitTest :feature-studio:testDebugUnitTest` passes
