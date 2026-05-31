## ADDED Requirements

### Requirement: Transcription Work Requires Adequate Storage

Background transcription SHALL be constrained from starting while device storage is low.

#### Scenario: Recording is queued for transcription

- **WHEN** transcription work is enqueued
- **THEN** its WorkManager constraints require battery-not-low and storage-not-low.

### Requirement: Processed Text Is Persisted At First Commit

Normal transcription SHALL persist word-replacement output when the transcript row is created.

#### Scenario: Word replacements run before enhancement

- **WHEN** the worker creates a transcript after local transcription
- **THEN** `processedText` and the processing mode are stored with the transcript row.
