## ADDED Requirements

### Requirement: Saved Recording Enhancement Uses Dedicated Work

Saved-recording LLM enhancement SHALL run in dedicated WorkManager work separate from offline transcription.

#### Scenario: Enhancement waits for network

- **WHEN** enhancement work is enqueued
- **THEN** its WorkManager constraints SHALL require network connectivity.

#### Scenario: Enhancement applies profile processing

- **WHEN** enhancement work runs for a profiled recording
- **THEN** it SHALL apply the persisted processing mode intent before title or summary generation
- **AND** metadata generation SHALL follow the persisted title and summary intent.

#### Scenario: Enhancement uses committed intent

- **WHEN** profile or global LLM settings change after transcription commits
- **AND** already queued enhancement work starts later
- **THEN** the enhancement worker SHALL use the enhancement intent persisted with the transcript
- **AND** it SHALL NOT re-resolve profile or global settings for that queued recording.

#### Scenario: Enhancement completes after all requested operations fail

- **WHEN** enhancement is requested
- **AND** every requested LLM operation returns failure
- **THEN** the failure SHALL be logged
- **AND** the recording SHALL become `COMPLETED` with the persisted transcription text preserved.
