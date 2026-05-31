## ADDED Requirements

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
