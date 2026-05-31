## 1. Implementation

- [x] 1.1 Add a voice-recognition capture gate over `RecordingStateManager`.
- [x] 1.2 Acquire the shared capture lock before `ChirpRecognitionService` starts `VoiceRecorder`.
- [x] 1.3 Acquire the shared capture lock before `VoiceRecognitionActivity` starts `VoiceRecorder`.
- [x] 1.4 Return recognizer-busy behavior when another recording origin owns the lock.
- [x] 1.5 Release the shared lock on stop, start failure, cancellation, and component destruction.

## 2. Tests

- [x] 2.1 Cover successful external recognition lock acquire and release.
- [x] 2.2 Cover busy rejection when app recording already owns the lock.
- [x] 2.3 Cover error release returning lock ownership for later recording.

## 3. Verification

- [x] 3.1 `./gradlew :app:testDebugUnitTest`
- [x] 3.2 `./gradlew :app:assembleDebug`
