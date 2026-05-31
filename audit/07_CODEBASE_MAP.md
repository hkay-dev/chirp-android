# Codebase Map

Use this as a starting map. Verify it against the live repo before relying on it.

## Modules

- `app`: application shell, navigation, DI, model download/readiness, recognizer wiring, recognition service.
- `core-contracts`: shared recording, transcription, quick-capture, model-readiness, utility, and reliability contracts.
- `core-audio`: audio recording primitives, encoder/writer, settings, input device selection, storage monitor.
- `core-playback`: shared Media3 playback controller and playback UI.
- `core-ui`: shared theme.
- `data`: Room database, DAOs, repositories, entities, migrations.
- `feature-recording`: app/widget recording lifecycle, service, segment capture, finalization, recovery, home/record UI.
- `feature-keyboard`: Chirp Voice IME, quick capture, keyboard UI, text insertion, haptics.
- `feature-transcription`: transcription workers, queues, audio decode/chunking, model manager, inline transcription coordination.
- `feature-widget`: home-screen widget.
- `feature-studio`: processing studio and transcript workflows.
- `feature-llm`: LLM cleanup, summaries, structured outcomes, recording-aware chat.
- `feature-obsidian`: Obsidian export.

## Lifecycle Invariants To Verify

- Only one capture session may hold the global recording lock.
- APP/WIDGET recording is service-backed, journaled, and has an in-progress DB row.
- KEYBOARD quick capture is inline and has no session journal.
- APP/WIDGET stop releases the capture lock after capture stop handoff, before background finalization finishes.
- Finalization serializes through WorkManager.
- STOPPING journals with in-progress DB rows are re-enqueued on startup.
- Recovery excludes the live active recording.
- Orphan cleanup respects referenced files and protected paths.
- Model readiness must never report ready for missing, corrupt, partial, or stale files.
- Keyboard text insertion must use a valid current input connection and avoid inserting into the wrong field after focus changes.
- Fixes should reduce the number of state owners whenever possible.
- Fallback behavior must be explicit, bounded, observable, and tested.
