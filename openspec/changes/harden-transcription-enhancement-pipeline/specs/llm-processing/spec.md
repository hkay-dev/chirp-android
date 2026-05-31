## ADDED Requirements

### Requirement: Saved Recording Enhancement Uses Full Execution Snapshots

Saved-recording enhancement SHALL execute from a persisted snapshot of the requested work rather than re-resolving mutable profile or global settings at execution time.

#### Scenario: Profile changes after enhancement is queued

- **GIVEN** transcription commits an enhancement snapshot for recording R
- **AND** the associated profile is edited before enhancement starts
- **WHEN** enhancement work runs
- **THEN** it SHALL use the processing mode, title request, and summary request stored in the snapshot
- **AND** it SHALL NOT change requested work based on the edited profile.

#### Scenario: Global settings change after profileless enhancement is queued

- **GIVEN** a profileless recording R has an enhancement snapshot
- **AND** global auto-title, auto-summary, processing mode, provider, or model settings change before enhancement starts
- **WHEN** enhancement work runs
- **THEN** it SHALL use the runtime-safe values stored in the snapshot
- **AND** it SHALL only consult current secret availability and network/runtime prerequisites.

#### Scenario: Snapshot excludes secrets

- **WHEN** an enhancement snapshot is persisted
- **THEN** API keys and other secrets SHALL NOT be stored in the database
- **AND** missing runtime credentials SHALL leave requested enhancement work recoverable.

### Requirement: Enhancement Results Are Execution-Guarded

Enhancement SHALL commit generated processed text, title, summary, and terminal status only when the active enhancement execution still matches the persisted snapshot.

#### Scenario: Late enhancement result loses ownership

- **GIVEN** enhancement work A starts for recording R
- **AND** a newer enhancement work B, retranscription, or user action supersedes A before it finishes
- **WHEN** work A attempts to commit generated outputs
- **THEN** the commit SHALL be rejected as stale
- **AND** work A SHALL NOT overwrite processed text, title, summary, snapshot status, or recording status
- **AND** a stale enhancement result event SHALL be logged.

#### Scenario: Enhancement result matches source revision

- **GIVEN** enhancement work A starts from transcript revision T1
- **AND** recording R still has revision T1 when A commits
- **WHEN** A completes requested subwork
- **THEN** its outputs SHALL be committed according to the snapshot subwork state.

#### Scenario: Transcript changes during enhancement

- **GIVEN** enhancement work A starts from transcript revision T1
- **AND** recording R is retranscribed or manually changed to revision T2 before A commits
- **WHEN** A completes
- **THEN** A SHALL be treated as stale
- **AND** T2 transcript data SHALL remain unchanged.

### Requirement: Requested Enhancement Subwork Is Retained Until Resolved

The enhancement pipeline SHALL preserve each requested enhancement subwork item until it succeeds, is explicitly skipped, or is superseded by a newer enhancement request.

#### Scenario: One requested subwork fails

- **GIVEN** an enhancement snapshot requests processing mode transform, title, and summary
- **WHEN** title generation succeeds and summary generation fails
- **THEN** the generated title SHALL be persisted
- **AND** the summary request SHALL remain in the snapshot with failure metadata
- **AND** retry SHALL run the failed summary request without rerunning the successful title request.

#### Scenario: All requested subwork fails

- **GIVEN** an enhancement snapshot has requested work
- **WHEN** every requested LLM operation fails after allowed attempts
- **THEN** the recording SHALL preserve its transcript text
- **AND** the enhancement snapshot SHALL retain the failed requested work
- **AND** retry SHALL enqueue enhancement work rather than transcription work.

#### Scenario: All requested subwork is resolved

- **GIVEN** every requested enhancement subwork item has succeeded or been explicitly skipped
- **WHEN** enhancement finalizes
- **THEN** the snapshot MAY be deleted
- **AND** the recording SHALL leave active enhancement status.
