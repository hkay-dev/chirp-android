## ADDED Requirements

### Requirement: Worker Result Commits Are Execution-Guarded

Background transcription SHALL commit transcript, timing, enhancement handoff, and recording status only for the execution that still owns the recording's transcription phase.

#### Scenario: Late transcription result loses ownership

- **GIVEN** transcription work A starts for recording R
- **AND** a newer transcription work B or user action claims R before A finishes
- **WHEN** work A attempts to commit its result
- **THEN** the commit SHALL be rejected as stale
- **AND** work A SHALL NOT overwrite transcript text, timings, enhancement snapshot, title, summary, or recording status
- **AND** a stale transcription result event SHALL be logged.

#### Scenario: Current transcription result owns the phase

- **GIVEN** transcription work A starts for recording R
- **AND** R is still owned by work A's transcription execution token
- **WHEN** work A commits transcript text and timings
- **THEN** the transcript and timings SHALL be persisted
- **AND** the recording status SHALL advance according to the requested enhancement snapshot.

#### Scenario: Transcription worker sees enhancement phase

- **GIVEN** recording R is `PENDING_ENHANCEMENT`
- **WHEN** stale transcription work runs for R
- **THEN** transcription work SHALL NOT perform local transcription
- **AND** SHALL NOT clear or replace the enhancement snapshot
- **AND** enhancement ownership SHALL remain with the enhancement queue path.
