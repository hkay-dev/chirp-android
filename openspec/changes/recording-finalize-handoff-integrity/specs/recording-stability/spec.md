## ADDED Requirements

### Requirement: Stop Journals Become Stopping Only After Capture Stop

The system SHALL keep a service-owned recording session recoverable as `ACTIVE` until the active capture segment has stopped and can be handed to background finalization.

#### Scenario: Process dies while capture stop is in progress

- **WHEN** app or widget recording stop has been requested but capture stop has not completed
- **THEN** the session journal remains recoverable and MUST NOT be treated as ready-only background finalize input.

### Requirement: Capture Handoff Is Recording-Scoped

The system SHALL release the global capture lock from a capture handoff only when the handoff belongs to the active recording.

#### Scenario: Stale handoff arrives after a newer recording starts

- **WHEN** a capture handoff carries an old recording id
- **THEN** the current active recording state remains unchanged and its lock remains held.

### Requirement: Existing Final Exports Are Idempotent

The system SHALL preserve and use an already-materialized playable final export for a segmented recording before depending on capture segment files.

#### Scenario: Final export exists but capture segments are gone

- **WHEN** a retry sees a playable `finalAudioPath` in the journal and missing capture segments
- **THEN** finalize uses the final export and MUST NOT abandon the recording as no-audio.

### Requirement: Finalize Foreground Work Declares Its Service Type

The system SHALL declare the runtime foreground service type for recording finalize foreground work when the platform requires typed foreground services.

#### Scenario: Finalize worker enters foreground

- **WHEN** background recording finalize work calls `setForeground`
- **THEN** the foreground info identifies the data-sync service type used by the merged WorkManager foreground service.

### Requirement: Starting Stop Cancels Startup Before Lock Release

The service SHALL cancel an in-flight `Starting` job before stop handoff releases the global capture lock.

#### Scenario: Stop arrives before a recording id exists

- **WHEN** stop is requested while service startup is still in `Starting`
- **THEN** the start job is cancelled and joined before the state manager is allowed to return to `Idle`.

### Requirement: Capture Stop Handoff Is Bounded

The service SHALL bound the active capture stop operation before enqueueing background finalization.

#### Scenario: Capture stop hangs

- **WHEN** active capture stop exceeds the handoff timeout
- **THEN** the service takes the explicit stop error path, abandons the session, and releases service resources.
