## Why

The current stop/finalize path can mark a session `STOPPING` before the active segment is finalized, can poison the shared WorkManager finalize chain after a terminal per-recording failure, and can release the global capture lock before an error-triggered stop has handed capture to finalize. These are audio-loss and stuck-state risks because startup recovery treats `STOPPING` as ready for background finalization and `ExistingWorkPolicy.APPEND` inherits failed prerequisites.

## What Changes

- Mark app/widget sessions `STOPPING` only after capture has stopped enough for background finalize.
- Treat terminal per-recording finalize cleanup as a successful WorkManager node so one bad recording does not block later finalize work.
- Keep service-owned error stops on the normal stop path until capture handoff completes.
- Declare the recording finalize foreground worker service type at runtime for Android 16 targeting.
- Ignore stale capture handoff callbacks whose recording id no longer matches the active state.
- Make segmented finalization idempotently accept an already-materialized playable export before reading deleted segment files.
- Cancel and join pre-id `Starting` service start jobs before releasing the global capture lock on stop.
- Bound service-owned capture stop handoff so a hung capture finalize moves to an explicit error path.
- Deduplicate startup finalize reconciliation when unfinished work already exists for the recording tag.
- Add focused unit tests for these invariants.

## Capabilities

### Modified Capabilities

- `recording-stability`: stop handoff and startup recovery preserve recoverable audio across process death windows.
- `queue-management`: the shared recording finalize pipeline continues after one recording reaches a terminal cleanup outcome.

## Impact

- Modules: `core-contracts`, `feature-recording`
- Depends on: none
- Verification: `./gradlew :core-contracts:testDebugUnitTest :feature-recording:testDebugUnitTest`, `./gradlew :app:assembleDebug`
