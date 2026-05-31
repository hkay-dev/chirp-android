## Why

Live app/widget capture can still lose the current in-flight M4A or MP3 segment if the process dies before the segment container is finalized. Previous stop, recovery, and worker hardening made normal stop and startup recovery durable, but encoded streaming containers still require a finalization step before they are reliably playable. That leaves a crash window during long unrotated live segments.

## What Changes

- Record live app/widget capture segments in WAV/PCM regardless of the user-selected export format.
- Keep the configured export path and saved recording format unchanged.
- Encode durable WAV segments to the selected M4A, MP3, or WAV export during stop/finalize.
- Preserve existing segmented recovery, checkpoint, and journal semantics using playable WAV segment artifacts.
- Add regression coverage for WAV-segment to M4A export materialization.
- Re-check existing LeakCanary signatures against current code and close any still-relevant strong-reference paths.

## Capabilities

### Modified Capabilities

- `recording-stability`: active live capture segments are crash-tolerant before stop/finalize completes.
- `recording`: saved recording output format remains user-selected even though live capture uses durable internal segments.

## Impact

- Modules: `core-audio`, `feature-recording`, `openspec`
- Depends on: none
- Verification: `./gradlew :core-audio:testDebugUnitTest :feature-recording:testDebugUnitTest`, `./gradlew :app:assembleDebug`, device install smoke check.
