## Context

The recording lifecycle spec requires one active microphone capture at a time. Service-owned app and widget recordings use `RecordingStateManager`, and keyboard quick capture uses the same manager. External recognition entry points were outside that path even though they still use the microphone.

## Decision

Represent external speech recognition as a keyboard-origin capture in the shared state manager. These sessions are short-lived inline dictation captures, not journaled app recordings. The gate acquires `RecordingOrigin.KEYBOARD`, starts the recorder only after acquisition, and releases the lock immediately after PCM capture stops.

Using `KEYBOARD` keeps widget and app stop routing from calling `RecordingService.stopRecording()` for a capture owned by `RecognitionService` or the dialog activity.

## Alternatives Considered

- Use `RecordingOrigin.APP`: rejected because stop command routing treats app-origin recording as `RecordingService`-owned.
- Add a fourth origin for external recognition: rejected for this change because existing stop routing and UI labels only distinguish app, widget, and keyboard ownership.
- Hold the shared lock through transcription: rejected because the invariant protects microphone capture, and transcription should not block new capture once audio has stopped.

## Risks

- A widget stop sent during external recognition can be recorded as a pending keyboard stop if no keyboard bridge is bound. Existing pending-stop reconciliation clears stale pending commands after the recording manager returns idle.

## Rollout

1. Add `VoiceRecognitionCaptureGate`.
2. Wire service and dialog activity capture through the gate.
3. Cover gate behavior with app unit tests.
4. Run app tests and debug APK build.
