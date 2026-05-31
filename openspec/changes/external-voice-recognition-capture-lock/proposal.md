## Why

The Android speech-recognition entry points used `VoiceRecorder` directly. `ChirpRecognitionService` and `VoiceRecognitionActivity` could start microphone capture without acquiring the shared `RecordingStateManager` lock, allowing external recognition to overlap app, widget, or keyboard recording.

## What Changes

- Add a small capture gate for external voice-recognition sessions.
- Route `ChirpRecognitionService` and `VoiceRecognitionActivity` through the shared recording lock before starting `VoiceRecorder`.
- Report recognizer busy when another recording origin owns the microphone.
- Release the shared lock when recognition capture stops, fails to start, is cancelled, or the host component is destroyed.
- Add focused unit coverage for acquire, busy rejection, and error release.

## Capabilities

### Modified Capabilities

- `recording-stability`: every app-owned microphone capture path participates in the single active capture invariant.

## Impact

- Modules: `app`
- Depends on: none
- Verification: `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`
