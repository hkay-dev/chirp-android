## ADDED Requirements

### Requirement: Retry Is Phase-Aware

Queue retry SHALL re-enqueue the failed processing phase rather than defaulting every failed recording to transcription.

#### Scenario: Failed transcription retry

- **GIVEN** recording R is `FAILED`
- **AND** no retained enhancement snapshot marks the latest failure as enhancement
- **WHEN** retry is requested for R
- **THEN** transcription work SHALL be enqueued
- **AND** R SHALL become `PENDING_TRANSCRIPTION`.

#### Scenario: Failed enhancement retry

- **GIVEN** recording R is `FAILED`
- **AND** R has a retained enhancement snapshot with unresolved requested subwork
- **WHEN** retry is requested for R
- **THEN** enhancement work SHALL be enqueued
- **AND** R SHALL become `PENDING_ENHANCEMENT`
- **AND** transcription work SHALL NOT be enqueued.

#### Scenario: Explicit full retranscription from enhancement failure

- **GIVEN** recording R has failed enhancement work
- **WHEN** the user explicitly requests full retranscription
- **THEN** transcription work MAY be enqueued
- **AND** existing enhancement snapshot work SHALL be superseded by the new transcription execution.

### Requirement: Pending Enhancement Recovery Is A Shared Contract

`PENDING_ENHANCEMENT` recovery SHALL be available through the shared transcription recovery contract.

#### Scenario: Contract exposes pending enhancement recovery

- **WHEN** a consumer depends on `TranscriptionRecovery`
- **THEN** it SHALL be able to call `recoverPendingEnhancement(recordingId)`
- **AND** the call SHALL not require access to `TranscriptionQueueManager`.

#### Scenario: Pending enhancement missing active work

- **GIVEN** recording R is `PENDING_ENHANCEMENT`
- **AND** no unfinished enhancement work owns R
- **WHEN** pending enhancement recovery is requested
- **THEN** enhancement work SHALL be enqueued
- **AND** transcription work SHALL NOT be enqueued.

#### Scenario: Pending enhancement already has active work

- **GIVEN** recording R is `PENDING_ENHANCEMENT`
- **AND** unfinished enhancement work owns R
- **WHEN** pending enhancement recovery is requested
- **THEN** recovery SHALL return a blocked-active-work result
- **AND** duplicate enhancement work SHALL NOT be enqueued.

### Requirement: Work Scheduling Is Injectable And Name-Stable

Transcription and enhancement queue orchestration SHALL use injectable WorkManager scheduling seams and production work name builders.

#### Scenario: Scheduler is faked in unit tests

- **WHEN** transcription queue unit tests exercise enqueue, cancel, retry, or reconciliation
- **THEN** tests SHALL use a fake scheduler or gateway
- **AND** SHALL NOT mock `WorkManager.getInstance`.

#### Scenario: Production work names are asserted

- **WHEN** tests verify unique work ownership
- **THEN** they SHALL assert names produced by production name builders
- **AND** SHALL NOT mock `TranscriptionWorkRequest.workName` or `RecordingEnhancementWorkRequest.workName`.

#### Scenario: Recording tag ownership remains shared

- **WHEN** work is scheduled for a recording
- **THEN** transcription and enhancement work SHALL include the shared recording tag for diagnostics
- **AND** each phase SHALL also use its own unique work name.
