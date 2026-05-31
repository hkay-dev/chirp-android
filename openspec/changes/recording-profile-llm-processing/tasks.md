## 1. Implementation

- [x] 1.1 Add a recording enhancement policy resolver.
- [x] 1.2 Apply profile default processing mode in the background worker.
- [x] 1.3 Use profile title and summary flags for profiled recordings.
- [x] 1.4 Preserve global title and summary preferences for recordings without a profile.
- [x] 1.5 Remove duplicate inline transcription cancellation handling.

## 2. Tests

- [x] 2.1 Cover profile policy overriding global preferences.
- [x] 2.2 Cover global fallback for recordings without a profile.
- [x] 2.3 Cover blank profile processing mode producing no requested work.

## 3. Verification

- [x] 3.1 `openspec validate recording-profile-llm-processing --strict`
- [x] 3.2 `./gradlew :feature-transcription:testDebugUnitTest`
