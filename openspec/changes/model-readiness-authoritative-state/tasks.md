## 1. Implementation

- [x] 1.1 Add readiness gate invalidation to the shared contract.
- [x] 1.2 Cancel stale in-flight readiness verification when invalidated.
- [x] 1.3 Invalidate readiness after model deletion.
- [x] 1.4 Validate required model files within one directory.
- [x] 1.5 Make recognizer loading use complete-directory validation.
- [x] 1.6 Guard invalidation and in-flight verification with one ownership lock.

## 2. Tests

- [x] 2.1 Cover invalidation clearing cached Ready state.
- [x] 2.2 Cover split persistent/legacy files not reporting Ready.
- [x] 2.3 Cover model deletion invalidating readiness.
- [x] 2.4 Cover invalidation racing an in-flight verification returning the fresh result.

## 3. Verification

- [x] 3.1 `./gradlew :app:testDebugUnitTest :feature-transcription:testDebugUnitTest`
