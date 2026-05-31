## 1. Implementation

- [x] 1.1 Add an input-session guard that tracks current IME generation.
- [x] 1.2 Detect password and no-personalized-learning input fields as sensitive.
- [x] 1.3 Route keyboard transcription commits through the guard.
- [x] 1.4 Stop keyboard recording when focus moves into a sensitive field.
- [x] 1.5 Cancel pending transcription when stop timeout cancels the keyboard session.

## 2. Tests

- [x] 2.1 Cover stale input sessions refusing late commits.
- [x] 2.2 Cover sensitive input fields refusing dictation commits.
- [x] 2.3 Cover current non-sensitive input committing text.

## 3. Verification

- [x] 3.1 `./gradlew :feature-keyboard:testDebugUnitTest`
