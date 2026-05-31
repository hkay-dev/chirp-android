## 1. Implementation

- [x] 1.1 Add OpenSpec proposal and design for crash-tolerant live capture.
- [x] 1.2 Add durable WAV segment path creation for live app/widget capture.
- [x] 1.3 Start, resume, and rotate service-owned capture using WAV segments.
- [x] 1.4 Encode WAV capture segments to the configured final export format.
- [x] 1.5 Preserve legacy encoded segment concatenation for old recoverable sessions.
- [x] 1.6 Re-check LeakCanary signatures against current strong-reference paths.

## 2. Tests

- [x] 2.1 Cover WAV segment to selected M4A export materialization.
- [x] 2.2 Run feature-recording unit tests.
- [x] 2.3 Run app debug build.

## 3. Verification

- [x] 3.1 `./gradlew :core-audio:testDebugUnitTest :feature-recording:testDebugUnitTest`
- [x] 3.2 `./gradlew :app:assembleDebug`
- [ ] 3.3 Install debug APK to Samsung.
