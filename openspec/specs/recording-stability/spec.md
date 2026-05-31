# recording-stability Specification

## Purpose
TBD - created by archiving change fix-medium-stability. Update Purpose after archive.
## Requirements
### Requirement: Atomic State Transitions
The RecordingStateManager SHALL use atomic compare-and-swap operations for all state transitions to prevent race conditions when multiple sources attempt concurrent state changes.

#### Scenario: Concurrent start attempts
- **WHEN** two sources (App and Keyboard) attempt to start recording simultaneously
- **THEN** exactly one SHALL succeed with RecordingStartResult.Success
- **AND** the other SHALL receive RecordingStartResult.AlreadyRecording

#### Scenario: Concurrent pause and stop
- **WHEN** pauseRecording() and beginStopRecording() are called concurrently
- **THEN** only one transition SHALL occur
- **AND** the final state SHALL be either Paused or Stopping, never corrupted

### Requirement: Stopping State Timeout Recovery
The RecordingStateManager SHALL recover from stuck Stopping states by invoking origin-specific timeout handlers that complete destructive cleanup **before** the manager transitions to Error and releases the recording lock. Timeout cleanup MUST NOT race with in-flight persist operations.

#### Scenario: Normal stop completes before timeout
- **GIVEN** a recording in Stopping state
- **WHEN** `onRecordingCompleted()` is called within the timeout period
- **THEN** the state SHALL transition to Idle
- **AND** the timeout timer SHALL be cancelled

#### Scenario: Stop hangs and times out (service-owned)
- **GIVEN** an APP or WIDGET recording in Stopping state
- **WHEN** `onRecordingCompleted()` is NOT called within the scaled timeout period
- **THEN** the service timeout handler SHALL abandon the session journal and delete the in-progress DB row **before** Error transition
- **AND** the state SHALL transition to Error with message "Failed to stop recording"
- **AND** the recording lock SHALL be released only after cleanup completes

#### Scenario: Persist still running when timeout fires
- **GIVEN** `persistAndQueueRecording` is in flight for stop generation N
- **WHEN** the stopping timeout fires and increments stop generation
- **THEN** the timeout handler SHALL abandon journal and delete in-progress row for generation N
- **AND** a late persist result for generation N SHALL be discarded
- **AND** `onRecordingCompleted()` SHALL NOT be called for the discarded result

#### Scenario: Stop hangs and times out (keyboard-owned)
- **GIVEN** a KEYBOARD recording in Stopping state during inline transcription
- **WHEN** completion is NOT called within the timeout period
- **THEN** the keyboard timeout handler SHALL discard captured samples and reset transcription phase
- **AND** the state SHALL transition to Error
- **AND** the recording lock SHALL be released

### Requirement: Unified Keyboard State Derivation
The ChirpKeyboardService SHALL derive its KeyboardState from the shared RecordingStateManager state combined with keyboard-specific signals, ensuring synchronization across all recording origins.

#### Scenario: Recording started from App
- **GIVEN** the keyboard is showing Idle state
- **WHEN** a recording is started from the main App
- **THEN** the keyboard SHALL reflect Recording state

#### Scenario: Recording stopped from Widget
- **GIVEN** a recording is active (started from any source)
- **WHEN** the recording is stopped from the Widget
- **THEN** the keyboard SHALL reflect Idle state (or Transcribing if applicable)

### Requirement: Thread-Safe Amplitude Updates
Amplitude tracking in RecordingStateManager and VoiceRecorder SHALL be thread-safe, preventing data races when updated from the audio capture thread.

#### Scenario: Rapid amplitude updates
- **WHEN** the audio capture thread emits amplitude values at 60Hz
- **AND** the UI thread reads amplitude history for visualization
- **THEN** no ConcurrentModificationException SHALL occur
- **AND** the amplitude history SHALL reflect recent values within 100ms

### Requirement: Thread-Safe Download Progress
WhisperModelManager download progress updates SHALL be atomic to prevent inconsistent state between progress percentage and status.

#### Scenario: Concurrent progress updates
- **WHEN** multiple download chunks complete simultaneously
- **AND** progress updates are emitted rapidly
- **THEN** the modelStatus and downloadProgress SHALL remain consistent
- **AND** UI observers SHALL never see progress > 0 with NotDownloaded status

### Requirement: Empty Recording Feedback
When a recording is stopped with insufficient audio samples, the system SHALL provide immediate user feedback rather than silently discarding the recording.

#### Scenario: Recording too short
- **GIVEN** a recording is started
- **WHEN** the user stops recording within 500 milliseconds
- **THEN** the system SHALL display "Recording too short" feedback
- **AND** no recording file SHALL be created

#### Scenario: No audio captured
- **GIVEN** a recording is started
- **WHEN** the audio capture returns zero samples
- **THEN** the system SHALL display "No audio recorded" feedback

### Requirement: Work Constraint User Feedback
When transcription work is blocked by system constraints (battery, storage), the user SHALL be informed of the specific reason and when transcription will resume.

#### Scenario: Battery low blocks transcription
- **GIVEN** a recording is completed and queued for transcription
- **WHEN** the device battery is below 15%
- **THEN** the system SHALL display "Transcription paused: Please charge your device"
- **AND** transcription SHALL automatically resume when battery is sufficient

#### Scenario: Storage low blocks transcription
- **GIVEN** a recording is completed and queued for transcription
- **WHEN** available storage is below 100MB
- **THEN** the system SHALL display "Transcription paused: Low storage space"

### Requirement: Safe Audio Format Handling
The AudioDecoder SHALL gracefully handle audio files with missing or invalid metadata by using safe accessors with sensible defaults.

#### Scenario: Missing sample rate metadata
- **GIVEN** an audio file with missing KEY_SAMPLE_RATE in MediaFormat
- **WHEN** the file is decoded
- **THEN** the decoder SHALL use a default sample rate of 44100 Hz
- **AND** decoding SHALL proceed normally

#### Scenario: Unsupported audio format
- **GIVEN** an audio file with MIME type "video/mp4" or other non-audio format
- **WHEN** the file is passed to decode()
- **THEN** the decoder SHALL throw AudioDecoderException
- **AND** the error message SHALL be user-friendly: "This audio format is not supported"

### Requirement: Main Thread Safety for SAF Operations
Storage Access Framework (SAF) operations SHALL NOT be performed on the main thread to prevent Application Not Responding (ANR) errors.

#### Scenario: Checking vault access
- **GIVEN** the ObsidianSettingsViewModel is checking vault access
- **WHEN** hasVaultAccess() is called
- **THEN** the call SHALL execute on the IO dispatcher
- **AND** the main thread SHALL not be blocked

#### Scenario: Refreshing access status on screen resume
- **GIVEN** the user returns to ObsidianSettingsScreen
- **WHEN** refreshAccessStatus() is called
- **THEN** the SAF operations SHALL execute on the IO dispatcher
- **AND** the UI SHALL show loading state during the check

### Requirement: Single KeyboardState Definition
The codebase SHALL have exactly one definition of the KeyboardState sealed interface to prevent code duplication and ensure consistent state handling.

#### Scenario: Delete duplicate KeyboardState
- **GIVEN** KeyboardState exists in both app/ and feature-keyboard/ modules
- **WHEN** the stability fixes are applied
- **THEN** only app/src/main/java/dev/chirpboard/app/KeyboardState.kt SHALL remain
- **AND** all imports SHALL reference the canonical location

### Requirement: Cancel Cleanup Before Lock Release

When a service-owned recording is canceled, the system SHALL complete session abandonment and artifact cleanup before releasing the global recording lock.

#### Scenario: Cancel then immediate new recording
- **GIVEN** the user confirms discard on the record screen
- **WHEN** cancel completes
- **THEN** the session journal SHALL be ABANDONED or deleted
- **AND** the in-progress DB row SHALL be removed
- **AND** a new recording MAY start without presenting recovery for the canceled session

#### Scenario: Cancel cleanup uses NonCancellable IO
- **GIVEN** cancel is requested while capture jobs are stopping
- **WHEN** coroutine scope is cancelled
- **THEN** file and DB cleanup SHALL still run to completion before `forceCancel()`

### Requirement: Keep Files Protected Path Retention

When the user selects **Keep files** on a recoverable session, the system SHALL remove the session journal immediately and protect referenced audio paths from orphan cleanup for seven days.

#### Scenario: Keep files preserves audio
- **GIVEN** a recoverable session with audio on disk
- **WHEN** the user selects Keep files
- **THEN** the session journal entry SHALL be removed
- **AND** referenced audio paths SHALL be written to durable protected-path storage with a seven-day TTL
- **AND** the in-progress database row SHALL be deleted

#### Scenario: Protected paths skipped by orphan cleaner
- **GIVEN** a kept session whose journal was removed
- **WHEN** orphan audio cleanup runs within the TTL window
- **THEN** protected audio paths SHALL NOT be deleted

### Requirement: Session Journal Reconciliation

The session journal reconciler SHALL remove stale entries when linked recordings are completed **or missing** from the database.

#### Scenario: Journal references completed recording

- **GIVEN** an active journal entry with recordingId R
- **AND** recording R has status other than `RECORDING`
- **WHEN** reconciliation runs
- **THEN** the journal entry SHALL be marked finalized
- **AND** capture artifacts for that session MAY be deleted

#### Scenario: Journal references missing recording row

- **GIVEN** an active journal entry with recordingId R
- **AND** no recording row exists for R
- **WHEN** reconciliation runs
- **THEN** the journal entry SHALL be marked finalized
- **AND** capture artifacts for that session SHALL be deleted

### Requirement: No-Audio Stop DB Hygiene

When stop finalize determines no recoverable audio exists, the system SHALL remove the in-progress recording database row before completing the stop lifecycle.

#### Scenario: Stop with missing audio file
- **GIVEN** an in-progress recording row exists
- **WHEN** stop yields `NoAudioFile`
- **THEN** the session journal SHALL be marked abandoned
- **AND** the in-progress DB row SHALL be deleted
- **AND** global state SHALL transition to Idle via `onRecordingCompleted()`
- **AND** no `RECORDING` status row SHALL remain in the database

### Requirement: Stale Pending Keyboard Stop Reconciliation

Stale pending keyboard stop entries SHALL be cleared only when global recording state confirms no active or in-progress keyboard stop is expected.

#### Scenario: Pending stop while keyboard session in Stopping

- **GIVEN** a pending keyboard stop exists
- **AND** global state is Stopping with KEYBOARD origin
- **WHEN** reconciliation runs on app or keyboard startup
- **THEN** the pending stop SHALL NOT be cleared prematurely
- **AND** drain MAY proceed when keyboard capture is still active

#### Scenario: Pending stop with idle global state

- **GIVEN** a pending keyboard stop exists
- **AND** global state is Idle or non-KEYBOARD without active capture
- **WHEN** reconciliation runs
- **THEN** the pending stop SHALL be cleared without user-visible side effects

### Requirement: Orphan Cleaner Format Parity

Maintenance orphan cleanup SHALL treat all configured recording output extensions consistently so disk reclamation does not favor one codec container.

#### Scenario: Mixed format orphans after format setting change

- **GIVEN** the user previously recorded MP3 and later uses M4A
- **WHEN** orphan cleaner runs
- **THEN** unreferenced files of each supported extension SHALL be eligible for cleanup under the same rules

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

### Requirement: External Recognition Acquires Shared Capture Lock

External Android speech-recognition capture SHALL acquire the shared recording lock before opening the microphone.

#### Scenario: Another origin is recording

- **WHEN** external speech recognition is requested while an app, widget, or keyboard recording is active
- **THEN** recognition reports busy and does not start microphone capture.

#### Scenario: External recognition capture stops

- **WHEN** external speech recognition stops, fails to start after lock acquisition, is cancelled, or its host component is destroyed
- **THEN** the shared recording lock is released for later capture.

### Requirement: Orphan Cleanup Handles Capture Directories

Recovery cleanup SHALL delete stale nested capture artifacts only when no repository, journal, safelist, or protected-path owner references them.

#### Scenario: Capture directory is stale and unreferenced

- **WHEN** `.capture/<session>` contains audio files older than the orphan grace window
- **AND** none of the files are referenced or protected
- **THEN** the capture directory is deleted recursively.

#### Scenario: Capture directory belongs to a live journal

- **WHEN** a capture segment path is referenced by a safelisted journal
- **THEN** orphan cleanup retains the capture directory and segment files.

## Audit backlog (2026-05-25)

Known gaps until archived changes land. Index: `openspec/changes/AUDIT_INDEX.md`.

| Priority | Gap | Change |
|----------|-----|--------|
| _(none for pending stop — see archive/2026-05-25-recording-edge-case-races)_ | | |
