## ADDED Requirements

### Requirement: Queue Ownership Distinguishes Transcription And Enhancement

Queue reconciliation SHALL enqueue and inspect the WorkManager unit that matches each pending recording status.

#### Scenario: Pending transcription is missing work

- **GIVEN** a recording is `PENDING_TRANSCRIPTION`
- **AND** no active transcription work owns it
- **WHEN** queue reconciliation runs
- **THEN** transcription work SHALL be enqueued.

#### Scenario: Pending enhancement is missing work

- **GIVEN** a recording is `PENDING_ENHANCEMENT`
- **AND** no active enhancement work owns its enhancement unique work name
- **WHEN** queue reconciliation runs
- **THEN** enhancement work SHALL be enqueued.

#### Scenario: Active transcription does not own pending enhancement

- **GIVEN** a recording is `PENDING_ENHANCEMENT`
- **AND** only transcription work is active for that recording
- **WHEN** queue reconciliation runs
- **THEN** enhancement work SHALL still be enqueued.

#### Scenario: Stale enhancement is recovered

- **GIVEN** a recording remains `ENHANCING` beyond the stale threshold
- **AND** no active enhancement work owns it
- **WHEN** queue reconciliation runs
- **THEN** the recording SHALL return to `PENDING_ENHANCEMENT`
- **AND** enhancement work SHALL be enqueued.

#### Scenario: Cancel processing for a recording

- **WHEN** processing is cancelled for a recording
- **THEN** pending or running transcription work SHALL be cancelled
- **AND** pending or running enhancement work SHALL be cancelled.
