## ADDED Requirements

### Requirement: Processing Studio Recovery Actions Cover Recoverable Queue States

Processing Studio SHALL expose recovery actions for all recoverable queue states and SHALL disable manual recovery while active work owns the recording.

#### Scenario: Pending transcription is visible

- **GIVEN** a recording is pending transcription and no active owner exists
- **WHEN** Processing Studio loads the recording
- **THEN** it shows a recovery action for transcription work.

#### Scenario: Pending enhancement is visible

- **GIVEN** a recording is pending enhancement and no active owner exists
- **WHEN** Processing Studio loads the recording
- **THEN** it shows a recovery action for enhancement work.

#### Scenario: Active ownership disables manual recovery

- **GIVEN** transcription or enhancement work is actively owned by a worker
- **WHEN** Processing Studio loads the recording
- **THEN** manual retry is disabled and the active state is shown.

#### Scenario: Failed state shows one retry banner

- **GIVEN** a recording has a terminal processing failure
- **WHEN** Processing Studio loads the recording
- **THEN** it shows one retry banner with the current failure reason.

### Requirement: Processing Studio Diagnostics Stay Fresh

Processing Studio SHALL refresh recovery diagnostics when status, error reason, active owner, or queue attempt metadata changes.

#### Scenario: Error reason changes under same status

- **GIVEN** a recording remains in the same failed status
- **WHEN** its failure reason changes
- **THEN** diagnostics refresh and show the new reason.

#### Scenario: Manual recovery refreshes ownership

- **GIVEN** a manual recovery action is requested
- **WHEN** ownership or queue metadata changes
- **THEN** Processing Studio refreshes the available actions.

### Requirement: Processing Studio Ignores Stale Diagnostics Results

Processing Studio SHALL apply diagnostics only when they still match the selected recording snapshot that produced them.

#### Scenario: Later transcript survives earlier diagnostics

- **GIVEN** diagnostics are running for a recording
- **WHEN** newer transcript content arrives before diagnostics finish
- **THEN** applying diagnostics does not replace the newer transcript content.

#### Scenario: Diagnostics for previous recording are dropped

- **GIVEN** diagnostics are running for one recording
- **WHEN** the user navigates to another recording
- **THEN** the earlier diagnostics result is ignored.

### Requirement: Studio Playback Reveal Is Scheduled Once Per Recording

Processing Studio SHALL schedule playback reveal work at most once per recording ID and audio path pair.

#### Scenario: Repeated emissions do not duplicate reveal jobs

- **GIVEN** the same recording and audio path are emitted repeatedly
- **WHEN** the playback reveal effect runs
- **THEN** only one reveal job is scheduled.

#### Scenario: Navigation cancels reveal

- **GIVEN** a playback reveal is pending
- **WHEN** the user leaves the recording
- **THEN** the pending reveal is cancelled.

#### Scenario: Audio path change reschedules reveal

- **GIVEN** the selected recording remains the same
- **WHEN** its audio path changes
- **THEN** a new reveal job is scheduled for the new path.
