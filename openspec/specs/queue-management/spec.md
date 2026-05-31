# queue-management Specification

## Purpose
TBD - created by archiving change transform-to-recording-app. Update Purpose after archive.
## Requirements
### Requirement: Transcription Queue

The system SHALL manage a queue of recordings awaiting transcription.

#### Scenario: Add to queue
- **WHEN** a recording is created with auto-transcribe enabled
- **THEN** it is added to the transcription queue
- **AND** Recording.status is set to PENDING_TRANSCRIPTION

#### Scenario: Process queue order
- **WHEN** multiple recordings are queued
- **THEN** they are processed in FIFO order
- **AND** only one transcription runs at a time

#### Scenario: Queue persistence
- **WHEN** app is killed during transcription
- **THEN** queue state persists in database
- **AND** processing resumes when app restarts

### Requirement: Queue Status

The system SHALL track and display queue status.

#### Scenario: Show queue count
- **WHEN** recordings are pending transcription
- **THEN** the home screen shows pending count
- **AND** pending recordings are visually distinct

#### Scenario: Show processing status
- **WHEN** a recording is being transcribed
- **THEN** it shows "Transcribing..." status
- **AND** progress percentage if available

#### Scenario: Show failed status
- **WHEN** transcription fails
- **THEN** the recording shows "Failed" status
- **AND** user can retry or view error

### Requirement: Queue Control

The system SHALL allow users to manage the queue.

#### Scenario: Cancel pending transcription
- **WHEN** user cancels a pending transcription
- **THEN** the recording is removed from queue
- **AND** status reverts to no transcript

#### Scenario: Retry failed transcription
- **WHEN** user retries a failed transcription
- **THEN** the recording is re-added to queue
- **AND** status changes to PENDING_TRANSCRIPTION

#### Scenario: Prioritize transcription
- **WHEN** user requests immediate transcription
- **THEN** the recording moves to front of queue
- **AND** starts processing after current item completes

### Requirement: Background Processing

The system SHALL process transcriptions reliably in background.

#### Scenario: Process while app backgrounded
- **WHEN** transcription is in progress
- **AND** user backgrounds the app
- **THEN** transcription continues via WorkManager
- **AND** notification shows progress

#### Scenario: Handle device restart
- **WHEN** device restarts with pending transcriptions
- **THEN** WorkManager reschedules the work
- **AND** processing resumes automatically

#### Scenario: Battery optimization
- **WHEN** transcription runs in background
- **THEN** foreground service ensures completion
- **AND** user is notified when complete

### Requirement: Recording Finalize Pipeline Continues After Terminal Cleanup

The system SHALL keep the shared recording finalize WorkManager chain available for later recordings after a single recording reaches a terminal cleanup outcome.

#### Scenario: One finalize item has no usable audio

- **WHEN** a finalize worker abandons or cleans up one recording because there is no usable audio or persistence failed terminally
- **THEN** the worker node completes without poisoning later `recording_finalize_pipeline` work.

### Requirement: Startup Finalize Recovery Is Recording-Deduped

Startup recovery SHALL NOT enqueue duplicate finalize work for a recording that already has unfinished finalize work.

#### Scenario: Process restarts after finalize was already scheduled

- **WHEN** startup reconciliation sees a `STOPPING` journal for a recording
- **AND** WorkManager already has unfinished work tagged for that recording
- **THEN** startup reconciliation skips enqueueing another finalize request for the same recording.
