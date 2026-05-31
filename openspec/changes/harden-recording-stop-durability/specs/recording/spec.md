## MODIFIED Requirements

### Requirement: Service-Owned Stop Outcomes

For APP and WIDGET origins, `RecordingService` SHALL map each stop persistence outcome to consistent journal, database, recovery, and global-state effects while preserving durable recovery handles for recoverable failures.

#### Scenario: Successful save and queue

- **GIVEN** stop finalize produces valid audio
- **WHEN** `persistAndQueueRecording` returns `SavedAndQueued`
- **THEN** the linked in-progress recording row SHALL be finalized to `PENDING_TRANSCRIPTION`
- **AND** the session journal SHALL be finalized
- **AND** transcription SHALL be enqueued.

#### Scenario: Queue handoff fails after save

- **GIVEN** stop finalize saves a valid recording
- **WHEN** queue enqueue fails
- **THEN** the recording row SHALL remain finalized
- **AND** queue recovery SHALL be marked for startup reconciliation
- **AND** the session journal SHALL be finalized.

#### Scenario: Recoverable finalize failure preserves handle

- **GIVEN** a session journal references an in-progress recording row
- **AND** at least one journal-referenced audio, checkpoint, or segment artifact exists
- **WHEN** background finalize fails before producing a completed recording
- **THEN** the in-progress recording row SHALL NOT be deleted
- **AND** the session journal SHALL remain recoverable
- **AND** startup recovery SHALL be able to retry or prompt using the same recording id.

#### Scenario: Unrecoverable no-audio cleanup

- **GIVEN** finalize finds no audio, checkpoint, or segment artifact referenced by the journal
- **WHEN** recovery assessment confirms the session is unrecoverable
- **THEN** the session journal MAY be abandoned
- **AND** the in-progress recording row MAY be deleted
- **AND** no duplicate finalized row SHALL be created.
