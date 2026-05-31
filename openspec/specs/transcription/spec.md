# transcription Specification

## Purpose
TBD - created by archiving change transform-to-recording-app. Update Purpose after archive.
## Requirements
### Requirement: Offline Transcription

The system SHALL transcribe audio using local Sherpa-ONNX engine without internet connectivity.

#### Scenario: Transcribe recording
- **WHEN** a recording is queued for transcription
- **THEN** the system processes audio through Sherpa-ONNX
- **AND** stores the resulting text in a Transcript entity

#### Scenario: Transcribe without internet
- **WHEN** device has no internet connection
- **THEN** transcription completes successfully
- **AND** only LLM features are unavailable

### Requirement: Background Transcription

The system SHALL process transcriptions in the background using WorkManager.

#### Scenario: Queue transcription
- **WHEN** a recording is saved
- **AND** auto-transcribe is enabled (via profile or default)
- **THEN** the recording is queued for background transcription
- **AND** Recording.status is set to PENDING_TRANSCRIPTION

#### Scenario: Process queue
- **WHEN** transcription worker runs
- **THEN** it processes recordings in queue order
- **AND** updates Recording.status through the pipeline

#### Scenario: Retry failed transcription
- **WHEN** transcription fails
- **THEN** the system retries up to 3 times with exponential backoff
- **AND** marks as FAILED after exhausting retries

### Requirement: Transcription Progress Display

Transcription progress UI SHALL reflect load failures and terminal states without indefinite indeterminate progress.

#### Scenario: Show progress notification
- **WHEN** transcription is in progress
- **THEN** a notification shows current progress
- **AND** includes the recording title if available

#### Scenario: Show progress in UI
- **WHEN** user views a recording being transcribed
- **THEN** the detail screen shows transcription progress
- **AND** displays estimated time remaining

#### Scenario: Studio open before recording row exists

- **GIVEN** Processing Studio is opened for a valid UUID
- **WHEN** the recording row is not yet available beyond a bounded loading window
- **THEN** progress UI MAY show a loading state
- **AND** SHALL transition to not-found or error if the row never appears

#### Scenario: Recording in FAILED status

- **GIVEN** transcription has failed for a recording
- **WHEN** progress components render
- **THEN** indeterminate transcribing/enhancing progress SHALL NOT be shown
- **AND** a terminal failure indicator SHALL replace progress animation

### Requirement: Word Replacement

The system SHALL apply word replacements during transcription.

#### Scenario: Apply replacements
- **WHEN** transcription completes
- **THEN** word replacements are applied to raw text
- **AND** the corrected text is stored

#### Scenario: Case-sensitive replacement
- **WHEN** a replacement is marked case-sensitive
- **THEN** only exact case matches are replaced

### Requirement: Manual Transcription

The system SHALL allow users to manually trigger transcription.

#### Scenario: Transcribe on demand
- **WHEN** user taps "Transcribe" on a recording without transcript
- **THEN** the recording is added to transcription queue
- **AND** Recording.status updates to PENDING_TRANSCRIPTION

#### Scenario: Re-transcribe
- **WHEN** user taps "Re-transcribe" on a completed recording
- **THEN** existing transcript is replaced with new transcription
- **AND** user is warned about losing existing transcript

### Requirement: Transcription Worker Bounded Wait

TranscriptionWorker SHALL NOT wait indefinitely for a recording to leave active processing states.

#### Scenario: Recording stuck in transcribing

- **GIVEN** a transcription work request for recording R
- **AND** R remains in an active transcription status beyond the configured maximum wait
- **WHEN** the worker evaluates readiness
- **THEN** the work SHALL fail or retry according to policy
- **AND** a reliability event SHALL be logged
- **AND** the worker process SHALL not block forever

### Requirement: Audio Decode Input Support

The transcription pipeline SHALL support common recording output formats with explicit failure when decode is unavailable.

#### Scenario: WAV input on supported device

- **GIVEN** recording audio is WAV format
- **WHEN** transcription begins
- **THEN** decode SHALL produce engine input or transcode successfully

#### Scenario: WAV decode unavailable (P4)

- **GIVEN** MediaCodec or platform decode fails for WAV
- **WHEN** transcription prep runs
- **THEN** the system SHALL attempt documented fallback (direct PCM read) or mark transcription FAILED with explicit reason
- **AND** SHALL NOT hang silently

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

### Requirement: Profile LLM Processing Applies To Saved Recording Pipeline

Saved app and widget recordings with an associated profile SHALL apply that profile's LLM processing settings after local transcription.

#### Scenario: Profile default mode transforms transcript

- **GIVEN** a saved recording has a profile with `defaultProcessingMode`
- **WHEN** background transcription completes local speech recognition and word replacement
- **THEN** the worker SHALL process the transcript with the profile's default mode
- **AND** store the transformed text as `processedText`.

#### Scenario: Profile metadata flags control enhancement

- **GIVEN** a saved recording has a profile
- **WHEN** background enhancement runs
- **THEN** title generation SHALL follow the profile `autoTitle` setting
- **AND** summary generation SHALL follow the profile `autoSummary` setting.

#### Scenario: No profile falls back to global enhancement preferences

- **GIVEN** a saved recording has no profile
- **WHEN** background enhancement runs
- **THEN** title and summary generation SHALL follow the global LLM preferences.

## Audit backlog (2026-05-25)

_(none — see archive/2026-05-25-transcription-pipeline-hardening)_

See `openspec/changes/AUDIT_INDEX.md`.
