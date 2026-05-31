## Why

Keyboard dictation can finish after the IME focus has moved to another field. Committing late text through the current input connection can insert stale transcript text into the wrong app field, including sensitive fields.

## What Changes

- Track input sessions by generation in `ChirpKeyboardService`.
- Refuse dictation commits when the input generation is stale, the input connection is gone, or the current field is sensitive.
- Stop active keyboard recording when focus moves into a sensitive field.
- Cancel pending inline transcription when the keyboard stop timeout cancels the recording session.

## Capabilities

### Modified Capabilities

- `keyboard-recording`: inline transcription only commits to the input session that requested it.

## Impact

- Modules: `feature-keyboard`
- Verification: `./gradlew :feature-keyboard:testDebugUnitTest`
