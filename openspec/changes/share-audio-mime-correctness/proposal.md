## Why

Share intents used `audio/m4a`, which is not the canonical MIME type for `.m4a` audio. This can reduce chooser compatibility and mislabel exported audio.

## What Changes

- Route studio and home audio share MIME types through `RecordingOutputFormat.fromFile`.
- Add studio share tests for `.m4a`, `.mp3`, and `.wav` MIME selection.

## Capabilities

### Modified Capabilities

- `recording-ui`: audio share intents use canonical MIME types derived from the file extension.

## Impact

- Modules: `feature-studio`, `feature-recording`, `core-audio`
- Verification: `./gradlew :feature-studio:testDebugUnitTest :feature-recording:testDebugUnitTest`
