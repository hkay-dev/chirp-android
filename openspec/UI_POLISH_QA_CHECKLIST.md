# UI polish manual QA checklist

Run on a physical device **before merging** any PR that touches home list, record flow, Processing Studio, nav shell, keyboard IME, or settings screens.

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
- [ ] Media player **expands down** and pushes tabs — not an instant pop-in
- [ ] Recovery/error blocks expand down smoothly when they appear

## Home list filters and empty states

- [ ] With recordings present, tap processing stat pill when count is **0** — list and stats **stay visible**; filter does not activate
- [ ] With processing recordings present, tap processing pill — list filters to processing items; pill shows selected state; dismissible **Processing** chip **slides in**
- [ ] Tap processing pill again (or chip dismiss) — filter clears; full list returns with smooth collapse
- [ ] Activate processing filter when no items match — stats row remains; inline “no match” message and **Clear filter** appear (not first-run empty state)
- [ ] First-run empty state appears **only** when there are zero recordings total and no active search

## Home search and list chrome

- [ ] Tap search icon — search field **expands down** and pushes the list (fully visible, not clipped)
- [ ] Type query — results filter; results count label **animates in**
- [ ] Clear query / close search — field collapses smoothly; list restores
- [ ] Recovery banner (if present) expands/collapses without snapping the list
- [ ] Transcribing list items grow/shrink progress banner smoothly as status changes

## Nav shell (when touched)

- [ ] Start playback from list — mini player **fades in**; content height adjusts smoothly
- [ ] Tap **X** on mini player — bar **fades out** smoothly (not an instant snap)
- [ ] Share audio into app — scrim overlay appears; Home/NavHost stays mounted underneath

## Keyboard & voice

- [ ] Keyboard dictation: mode controls row expands down after recording stops
- [ ] Voice recognition sheet: content reflows smoothly when waveform/mode areas change

## Settings (spot check)

- [ ] LLM settings: enabling LLM expands API key + processing sections smoothly
- [ ] Transcription model download: progress bar expands in/out
- [ ] Audio settings: switching to manual input device list expands device rows smoothly

## Regression smoke (quick)

- [ ] `./gradlew :app:assembleDebug :feature-recording:testDebugUnitTest :feature-studio:testDebugUnitTest` passes
