## Why

Transcription work could run under low-storage conditions and the worker persisted only raw text before enhancement, losing the word-replacement processed text until later stages.

## What Changes

- Add a storage-not-low constraint to transcription work.
- Persist word-replacement processed text and processing mode when normal transcription creates the transcript row.
- Keep runtime foreground-service type declarations aligned with API 36.

## Capabilities

### Modified Capabilities

- `transcription`: transcription work waits for adequate storage and stores processed transcript text at first commit.

## Impact

- Modules: `feature-transcription`
- Verification: `./gradlew :feature-transcription:testDebugUnitTest`
