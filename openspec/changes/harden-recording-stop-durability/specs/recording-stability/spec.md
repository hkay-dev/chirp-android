## ADDED Requirements

### Requirement: Stop And Segment Rotation Are Serialized

The service SHALL serialize timer rotation, pause finalization, resume segment creation, and stop finalization for a recording session.

#### Scenario: Stop arrives during segment rotation

- **WHEN** stop is requested while timer rotation is pending or active
- **THEN** stop waits for the segment transition boundary
- **AND** finalize input includes the completed pre-rotation segment and the active tail segment exactly once.

#### Scenario: Rotation completes as stop snapshots state

- **WHEN** rotation changes the active segment path near stop
- **THEN** the stop snapshot uses the post-transition active segment path
- **AND** the journal ordered segment list remains complete.

### Requirement: Final Segment Is Committed Before Background Finalize

The service SHALL commit the stopped active segment to durable journal state before enqueueing background finalize work.

#### Scenario: Process dies after capture stop before worker starts

- **WHEN** capture stop has returned a final active segment
- **AND** the process dies before `RecordingFinalizeWorker` runs
- **THEN** startup recovery sees the tail segment in the session journal
- **AND** recovery or finalize can materialize the full recording.

### Requirement: Native Capture Finalization Is Hard Bounded

The capture stop deadline SHALL bound the service stop operation even when native recorder, encoder, writer, or audio release calls block.

#### Scenario: Native stop hangs

- **WHEN** native capture finalization exceeds the configured deadline
- **THEN** the stop operation returns through an explicit timeout path within that deadline
- **AND** late native completion is fenced from mutating journal, database, or global state
- **AND** recoverable journal and database handles are preserved.

### Requirement: RecordingService Destroy Does Not Block Main Lifecycle

`RecordingService.onDestroy()` SHALL NOT synchronously block the service lifecycle on capture finalization, segment concatenation, database writes, or WorkManager enqueue.

#### Scenario: Service is destroyed while capture is active

- **WHEN** Android destroys `RecordingService` during an active APP or WIDGET recording
- **THEN** `onDestroy()` records or preserves recoverable session state
- **AND** releases callbacks and periodic jobs without `runBlocking`
- **AND** startup recovery can resume from the durable journal and in-progress row.

### Requirement: Finalize Failures Preserve Recovery Identity

Recoverable finalize failure handling SHALL preserve the recording identity needed for idempotent recovery.

#### Scenario: Background finalize fails after handoff

- **GIVEN** the global capture lock has already been released
- **WHEN** background finalize fails with recoverable session artifacts still present
- **THEN** the linked in-progress recording id remains the recovery target
- **AND** a later recovery attempt SHALL finalize that row rather than creating a duplicate recording.
