## ADDED Requirements

### Requirement: Offline Transcription Is Decoupled From LLM Enhancement

Background transcription SHALL complete local speech recognition and transcript persistence without running network-backed LLM enhancement inside the transcription worker.

#### Scenario: Transcription succeeds without requested enhancement

- **WHEN** the transcription worker persists transcript text and timings
- **AND** no enhancement work is requested
- **THEN** the recording SHALL become `COMPLETED`
- **AND** no enhancement work SHALL be enqueued.

#### Scenario: Transcription queues requested enhancement

- **WHEN** the transcription worker persists transcript text and timings
- **AND** enhancement work is requested and available
- **THEN** the transcript and enhancement intent SHALL be committed atomically
- **AND** the recording SHALL become `PENDING_ENHANCEMENT`
- **AND** a separate enhancement work request SHALL be enqueued.

#### Scenario: Transcription completes offline when enhancement unavailable

- **WHEN** the transcription worker persists transcript text and timings
- **AND** enhancement is requested but LLM is disabled or no API key is configured
- **THEN** enhancement SHALL be logged as skipped
- **AND** the recording SHALL become `COMPLETED`.
