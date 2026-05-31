## 1. Stop And Finalize Durability

- [x] 1.1 Update finalize outcome appliers so recoverable `PersistenceFailed` and `NoAudioFile` outcomes preserve the in-progress row when journal-referenced artifacts exist.
- [x] 1.2 Add a repository API for explicit user-abandon cleanup so automatic finalize failure cleanup does not call `deleteInProgressRecording()`.
- [x] 1.3 Update recovery and startup reconciliation to retry or prompt from preserved `STOPPING` journals with linked `RECORDING` rows.
- [x] 1.4 Add tests proving finalize failure keeps the durable recording handle and recovery finalizes the same row.

## 2. Segment Stop Ordering

- [x] 2.1 Route stop, pause, resume, and timer rotation through one segment transition coordinator or shared mutex boundary.
- [x] 2.2 Commit the final stopped segment path to the journal before marking `STOPPING`.
- [x] 2.3 Add a stop-vs-rotation test where the tail segment is included exactly once.

## 3. Native Stop Bounds

- [x] 3.1 Replace coroutine-only timeout around `stopAndFinalize()` with a bounded capture stop result.
- [x] 3.2 Fence late native stop results by stop generation/session id.
- [x] 3.3 Add a fake hanging capture engine test proving stop timeout returns within budget and preserves recovery handles.

## 4. Service Lifecycle

- [x] 4.1 Extract service stop/capture/session cleanup from `RecordingService`.
- [x] 4.2 Remove blocking work from `onDestroy()`.
- [x] 4.3 Add tests for `onDestroy()` cleanup ordering and active-session recovery state.

## 5. Keyboard Persistence

- [x] 5.1 Make `KeyboardInlineCapturePersistence.persist()` await file encode and Room write before returning.
- [x] 5.2 Keep optional Obsidian export separate from durable local persistence.
- [x] 5.3 Add cancellation tests proving keyboard persistence is not lost when transcription completes or IME teardown begins.

## 6. Verification

- [x] 6.1 Run targeted unit tests for recording, keyboard, transcription, and core contracts.
- [x] 6.2 Run `./gradlew :app:assembleDebug`.
