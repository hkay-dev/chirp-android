## Why

The saved recording pipeline ignored profile LLM defaults after local transcription. Profiles exposed `defaultProcessingMode`, `autoTitle`, and `autoSummary`, but app and widget recordings only used global title and summary preferences in background enhancement. That made profile-level LLM processing appear configurable while the recording-to-transcript flow skipped it.

## What Changes

- Resolve an enhancement policy from the recording profile before background LLM work.
- Apply the profile default processing mode to `processedText` before generating title or summary.
- Use profile title and summary flags for profiled recordings, while preserving global preferences for recordings without a profile.
- Log requested-but-failed enhancement as failure rather than "not requested".
- Remove an unreachable duplicate cancellation handler from inline transcription.

## Capabilities

### Modified Capabilities

- `transcription`: saved recording enhancement applies profile LLM defaults after local transcription.
- `llm-processing`: profile processing modes control background transcript transformation.
- `profiles`: profile auto-title and auto-summary settings affect app and widget recordings.

## Impact

- Modules: `feature-transcription`, `feature-llm`, `data`
- Verification: `./gradlew :feature-transcription:testDebugUnitTest`, `openspec validate recording-profile-llm-processing --strict`
