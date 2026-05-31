## ADDED Requirements

### Requirement: Enhancement Snapshot Migration Preserves Pending Work

Database migrations that introduce or alter enhancement execution snapshots SHALL preserve all pending enhancement work without destructive fallback.

#### Scenario: Profile-backed pending enhancement migrates

- **GIVEN** a legacy recording is `PENDING_ENHANCEMENT`
- **AND** it has a profile with enhancement settings in the database
- **WHEN** the database migrates
- **THEN** an enhancement execution snapshot SHALL be created for the recording
- **AND** requested processing, title, and summary work SHALL reflect the profile settings available at migration time.

#### Scenario: Profileless pending enhancement migrates

- **GIVEN** a legacy recording is `PENDING_ENHANCEMENT`
- **AND** it has no profile
- **WHEN** the database migrates
- **THEN** the recording SHALL NOT be silently skipped
- **AND** a recoverable legacy enhancement snapshot SHALL be created
- **AND** the recording SHALL remain eligible for pending enhancement recovery.

#### Scenario: Enhancing recording migrates

- **GIVEN** a legacy recording is `ENHANCING`
- **WHEN** the database migrates
- **THEN** a recoverable enhancement snapshot SHALL be created
- **AND** startup reconciliation SHALL be able to move it to `PENDING_ENHANCEMENT` if no active enhancement work owns it.

#### Scenario: Migration preserves transcript and metadata

- **WHEN** enhancement snapshot migration runs
- **THEN** existing recordings, transcripts, timings, titles, summaries, and manual corrections SHALL be preserved
- **AND** destructive migration fallback SHALL NOT be used.
