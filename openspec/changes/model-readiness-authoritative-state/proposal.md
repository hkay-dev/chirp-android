## Why

Model readiness was cached across destructive model changes and could accept required files split across persistent and legacy directories. That can report Ready while no single loadable model directory exists.

## What Changes

- Add explicit readiness invalidation to the shared readiness gate.
- Make invalidation and in-flight verification share one ownership lock so stale verification results cannot escape to callers.
- Invalidate readiness after model deletion before re-evaluating model files.
- Validate model completeness per directory, not by merging files from persistent and legacy locations.
- Make recognizer path selection use the same complete-directory rule.

## Capabilities

### Modified Capabilities

- `model-readiness`: readiness state is invalidated by destructive model changes and only accepts a single complete model directory.

## Impact

- Modules: `core-contracts`, `app`, `feature-transcription`
- Verification: `./gradlew :app:testDebugUnitTest :feature-transcription:testDebugUnitTest`
