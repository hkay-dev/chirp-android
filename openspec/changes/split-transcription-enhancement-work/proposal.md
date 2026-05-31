## Why

Saved recording transcription is offline-capable, but the current `TranscriptionWorker` also runs network-backed LLM enhancement before it completes. That couples local speech recognition to network availability and LLM latency, keeps one worker responsible for two failure domains, and makes recovery ownership ambiguous for `PENDING_ENHANCEMENT`.

## What Changes

- Keep local transcription in `TranscriptionWorker`.
- Move profile/default LLM transform, auto-title, and auto-summary into a separate enhancement worker.
- Queue enhancement work only when LLM enhancement is requested and available.
- Add network connectivity as a WorkManager constraint for enhancement work, not transcription work.
- Make queue reconciliation choose transcription or enhancement work based on the recording status.
- Preserve current skip behavior when no enhancement is requested or LLM is unavailable.

## Capabilities

### Modified Capabilities

- `transcription`: offline transcription completes after transcript and timings are persisted.
- `llm-processing`: saved-recording LLM enhancement runs in its own network-constrained worker.
- `queue-management`: pending transcription and pending enhancement have separate WorkManager ownership.

## Impact

- Modules: `feature-transcription`, `feature-llm`, `data`
- Verification: `openspec validate split-transcription-enhancement-work --strict`, `./gradlew :feature-transcription:testDebugUnitTest`, `./gradlew :app:assembleDebug`
