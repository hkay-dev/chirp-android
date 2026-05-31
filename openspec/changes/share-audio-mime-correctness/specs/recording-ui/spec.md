## ADDED Requirements

### Requirement: Audio Shares Use Canonical MIME Types

Audio share intents SHALL derive their MIME type from the recording file format.

#### Scenario: Sharing an M4A recording

- **WHEN** the user shares an `.m4a` recording
- **THEN** the share intent type is `audio/mp4`.

#### Scenario: Sharing MP3 or WAV recordings

- **WHEN** the user shares `.mp3` or `.wav` recordings
- **THEN** the share intent type is `audio/mpeg` or `audio/wav` respectively.
