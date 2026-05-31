## Why

The saved recording WorkManager pipeline has phase and snapshot gaps after splitting transcription from enhancement. Stale workers can still commit late results, enhancement retries can fall back to full transcription, queued enhancement work does not carry a full execution snapshot, legacy profileless pending enhancement can be skipped during migration, and `PENDING_ENHANCEMENT` recovery is not first-class in the shared contract or UI.

## What Changes

- Guard transcription and enhancement commits with phase execution tokens so late worker results cannot overwrite newer state.
- Persist a full enhancement execution snapshot, including requested subwork, source transcript revision, resolved processing mode details, and runtime-safe model settings.
- Preserve failed enhancement subwork until it succeeds, is retried, or is explicitly skipped.
- Route failed enhancement retry through enhancement work, not transcription work.
- Make `PENDING_ENHANCEMENT` recovery part of the core recovery contract and expose it in Home and Processing Studio.
- Migrate all legacy `PENDING_ENHANCEMENT` and `ENHANCING` recordings into recoverable enhancement snapshots, including profileless rows.
- Replace static WorkManager helper usage with injectable scheduling seams and tests that assert production work names without mocking helper names.

## Capabilities

### Modified Capabilities

- `transcription`: worker result commits are stale-safe.
- `llm-processing`: saved-recording enhancement uses durable execution snapshots and retains failed requested subwork.
- `queue-management`: retry and recovery are phase-aware for transcription and enhancement.
- `recording-ui`: pending enhancement recovery is visible and actionable.
- `data-persistence`: enhancement snapshot migrations preserve legacy pending enhancement work.

## Impact

- Modules: `core-contracts`, `data`, `feature-transcription`, `feature-recording`, `feature-studio`
- Verification: `openspec validate harden-transcription-enhancement-pipeline --strict`, `./gradlew :data:compileDebugAndroidTestKotlin :data:connectedDebugAndroidTest :feature-transcription:testDebugUnitTest :feature-recording:testDebugUnitTest :feature-studio:testDebugUnitTest`
