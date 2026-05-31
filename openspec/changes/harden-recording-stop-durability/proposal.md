## Why

Recording stop has five durability gaps:

- Finalize failure cleanup can delete the in-progress database row that recovery needs as the durable recording handle.
- Stop can run while segment rotation is changing the active segment path, so finalize can omit the tail segment.
- `withTimeout` around a blocking native `stopAndFinalize()` call does not actually bound native finalization.
- `RecordingService.onDestroy()` can block the service lifecycle while also owning too many stop, recovery, rotation, and cleanup concerns.
- Keyboard inline capture persistence launches background work from a suspending API, so callers can complete before audio file and database writes finish.

## What Changes

- Preserve in-progress recording rows and session journals for recoverable stop/finalize failures.
- Serialize stop, pause, resume, and timed segment rotation through one segment transition owner.
- Replace soft coroutine timeout around native finalization with a hard bounded capture-stop contract that fences late results.
- Extract service stop/capture/session cleanup collaborators and make `onDestroy()` non-blocking.
- Make keyboard capture persistence await durable file/database completion before reporting completion.

## Capabilities

### Modified Capabilities

- `recording`: service-owned stop outcomes preserve durable recovery handles.
- `recording-stability`: capture stop, segment rotation, native timeout, service destruction, and recovery are bounded and race-safe.
- `keyboard-recording`: saved keyboard recordings are durable before completion callbacks run.

## Impact

- Modules: `feature-recording`, `core-contracts`, `feature-keyboard`, `feature-transcription`, `data`
- Expected tests: stop/finalize failure recovery, stop-vs-rotation tail inclusion, native stop timeout fake, service destroy non-blocking, keyboard persistence cancellation
- Verification: `./gradlew :core-contracts:testDebugUnitTest :feature-recording:testDebugUnitTest :feature-keyboard:testDebugUnitTest :feature-transcription:testDebugUnitTest :app:assembleDebug`

## Non-Goals

- Changing user-visible recording segmentation.
- Parallelizing the finalize pipeline.
- Moving keyboard quick capture into `RecordingService`.
