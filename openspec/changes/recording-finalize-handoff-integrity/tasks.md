## 1. Implementation

- [x] 1.1 Move service `STOPPING` journal transition after capture stop and before finalize enqueue.
- [x] 1.2 Route service-owned device/storage error stops through normal stop handoff without pre-releasing the lock.
- [x] 1.3 Guard capture handoff by recording id in `RecordingStateManager`.
- [x] 1.4 Return successful WorkManager results after terminal per-recording finalize cleanup.
- [x] 1.5 Reuse an existing playable segmented export before requiring segment inputs.
- [x] 1.6 Set the runtime foreground service type for recording finalize work.
- [x] 1.7 Cancel and join a `Starting` service job before stop releases the lock.
- [x] 1.8 Bound capture stop handoff with a service-owned timeout.
- [x] 1.9 Skip startup finalize re-enqueue when unfinished work exists for the same recording tag.

## 2. Tests

- [x] 2.1 Cover stale handoff not clearing a newer active recording.
- [x] 2.2 Cover terminal finalize outcomes continuing the worker chain.
- [x] 2.3 Cover existing segmented export reuse when capture segments are gone.
- [x] 2.4 Cover `STOPPING` marking after capture stop.
- [x] 2.5 Cover recording finalize foreground info service type.
- [x] 2.6 Cover finalize work request policy and recording tag.
- [x] 2.7 Cover startup finalize dedupe for unfinished recording-tag work.

## 3. Verification

- [x] 3.1 `./gradlew :core-contracts:testDebugUnitTest :feature-recording:testDebugUnitTest`
- [x] 3.2 `./gradlew :app:assembleDebug`
