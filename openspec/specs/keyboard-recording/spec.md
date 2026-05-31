# keyboard-recording Specification

## Purpose
TBD - created by archiving change fix-high-ux-reliability. Update Purpose after archive.
## Requirements
### Requirement: Audio Recording Error Detection

The keyboard voice recorder SHALL detect and report all AudioRecord errors to the user with actionable messages.

#### Scenario: Microphone unavailable during recording
- **WHEN** `AudioRecord.read()` returns `ERROR_DEAD_OBJECT`
- **THEN** recording SHALL stop immediately
- **AND** keyboard SHALL display error "Microphone disconnected"
- **AND** `RecordingStateManager.onRecordingError()` SHALL be called

#### Scenario: Recording not properly initialized
- **WHEN** `AudioRecord.read()` returns `ERROR_INVALID_OPERATION`
- **THEN** recording SHALL stop immediately
- **AND** keyboard SHALL display error "Microphone not ready"

#### Scenario: Invalid recording parameters
- **WHEN** `AudioRecord.read()` returns `ERROR_BAD_VALUE`
- **THEN** recording SHALL stop immediately
- **AND** keyboard SHALL display error "Recording configuration error"

#### Scenario: Unknown recording error
- **WHEN** `AudioRecord.read()` returns any negative value not matching known error codes
- **THEN** recording SHALL stop immediately
- **AND** keyboard SHALL display error "Recording failed (code: X)"

---

### Requirement: Audio Focus Management

The keyboard SHALL request exclusive audio focus before recording and release it when recording stops.

#### Scenario: Successful audio focus acquisition
- **WHEN** user taps record button
- **THEN** system SHALL request `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`
- **AND** recording SHALL only begin after focus is granted

#### Scenario: Audio focus denied
- **WHEN** audio focus request is denied (another app has priority)
- **THEN** recording SHALL NOT start
- **AND** keyboard SHALL display error "Another app is using audio"

#### Scenario: Audio focus lost during recording
- **WHEN** recording is active
- **AND** audio focus is lost (`AUDIOFOCUS_LOSS` or `AUDIOFOCUS_LOSS_TRANSIENT`)
- **THEN** recording SHALL stop via `stopAndTranscribe`
- **AND** pending widget stop queue SHALL NOT duplicate stop if already draining

#### Scenario: Audio focus released after recording
- **WHEN** recording stops (success, error, or user-initiated)
- **THEN** audio focus SHALL be abandoned
- **AND** other apps SHALL be able to resume audio playback

---

### Requirement: Phone Call Interruption Handling

The keyboard SHALL stop recording when a phone call begins to prevent capturing silence.

#### Scenario: Incoming call during recording
- **WHEN** recording is active
- **AND** phone state changes to `CALL_STATE_RINGING`
- **THEN** recording SHALL stop immediately
- **AND** keyboard SHALL display toast "Recording stopped: phone call"
- **AND** transcription SHALL proceed with captured audio

#### Scenario: Outgoing call during recording
- **WHEN** recording is active
- **AND** phone state changes to `CALL_STATE_OFFHOOK`
- **THEN** recording SHALL stop immediately
- **AND** keyboard SHALL display toast "Recording stopped: phone call"

#### Scenario: Phone permission not granted
- **WHEN** `READ_PHONE_STATE` permission is not granted
- **THEN** phone call detection SHALL be disabled silently
- **AND** recording SHALL function normally without call interruption handling
- **AND** a warning SHALL be logged

---

### Requirement: Recording Synchronization

The keyboard SHALL capture audio from the first moment of recording with no data loss.

#### Scenario: No audio loss at recording start
- **WHEN** user taps record button
- **THEN** `collectSamples()` SHALL NOT begin reading until `AudioRecord.startRecording()` has completed
- **AND** no audio frames SHALL be missed between start and first sample read

#### Scenario: Recording too short
- **WHEN** user taps record and stop within 300 milliseconds
- **THEN** recording SHALL be discarded
- **AND** keyboard SHALL display error "Recording too short"
- **AND** no transcription SHALL be attempted

#### Scenario: Samples synchronized on stop
- **WHEN** `stop()` is called
- **THEN** all samples collected up to that point SHALL be returned
- **AND** no samples SHALL be lost or duplicated

---

### Requirement: Audio Decoding Performance

The audio decoder SHALL process recordings in linear time regardless of duration.

#### Scenario: Efficient chunk buffer operations
- **WHEN** decoding audio to PCM samples
- **THEN** `ChunkBuffer` operations SHALL be O(1) per sample
- **AND** a 30-second recording (480,000 samples) SHALL decode in under 1 second

#### Scenario: Memory efficiency
- **WHEN** processing large audio files
- **THEN** memory usage SHALL NOT grow beyond 2x the chunk size
- **AND** samples SHALL be emitted incrementally via callback

---

### Requirement: Keyboard Recording Persistence

The keyboard SHALL save recordings to the database when the "Save keyboard recordings" setting is enabled.

#### Scenario: Save keyboard recording enabled
- **WHEN** user has enabled "Save keyboard recordings" setting
- **AND** user completes a keyboard voice recording
- **THEN** PCM samples SHALL be encoded to M4A format
- **AND** M4A file SHALL be saved to `filesDir/recordings/` directory
- **AND** a `Recording` entity SHALL be created with `source = RecordingSource.KEYBOARD`
- **AND** a `Transcript` entity SHALL be created with raw and processed text
- **AND** recording status SHALL be `COMPLETED`

#### Scenario: Keyboard recording appears in app
- **WHEN** a keyboard recording is saved
- **THEN** it SHALL appear in the home screen recording list
- **AND** it SHALL display a "Keyboard" source badge
- **AND** tapping it SHALL play the audio

#### Scenario: Save keyboard recording disabled
- **WHEN** user has disabled "Save keyboard recordings" setting (default)
- **AND** user completes a keyboard voice recording
- **THEN** no M4A file SHALL be created
- **AND** no `Recording` entity SHALL be created
- **AND** transcription SHALL still be inserted into the text field

#### Scenario: Encoding failure fallback
- **WHEN** M4A encoding fails
- **THEN** recording SHALL NOT be saved
- **AND** error SHALL be logged
- **AND** transcription SHALL still be inserted into the text field
- **AND** user experience SHALL NOT be blocked

---

### Requirement: Pending Stop Drain

The keyboard service SHALL consume durable pending stop requests when it becomes active.

#### Scenario: Drain on service create
- **GIVEN** a pending keyboard stop was enqueued by widget or app
- **WHEN** `ChirpKeyboardService` is created and keyboard recording is active
- **THEN** `KeyboardSessionCoordinator.stopAndTranscribe()` SHALL run
- **AND** the pending stop SHALL be cleared

### Requirement: Pending Keyboard Stop Drain Preconditions

The keyboard service SHALL drain pending stop requests only when keyboard-origin recording or stopping is active, and SHALL reconcile stale entries consistent with global state.

#### Scenario: Drain on bind with active keyboard recording

- **GIVEN** pending keyboard stop exists
- **AND** keyboard quick-capture is actively recording
- **WHEN** `ChirpKeyboardService` binds
- **THEN** stop-and-transcribe SHALL run
- **AND** pending stop SHALL be cleared in finally

#### Scenario: Stale pending stop without keyboard session

- **GIVEN** pending keyboard stop exists
- **AND** no keyboard recording is active
- **WHEN** keyboard service starts
- **THEN** pending stop SHALL be cleared without discarding unrelated APP/WIDGET sessions

---

### Requirement: Keyboard Stopping Timeout Cleanup

The keyboard coordinator SHALL register a stopping timeout handler for KEYBOARD-origin sessions.

#### Scenario: Inline transcription hang
- **GIVEN** keyboard recording stopped and inline transcription is in Stopping
- **WHEN** the stopping timeout elapses
- **THEN** captured samples SHALL be discarded or persisted with explicit error
- **AND** transcription phase SHALL reset
- **AND** `RecordingStateManager` SHALL transition to Error

### Requirement: Widget Keyboard Stop Is Durable Before Acknowledgement

The system SHALL persist a widget-requested pending stop for an unbound keyboard recording before reporting that the stop was queued.

#### Scenario: Widget stops keyboard recording while IME is unbound

- **WHEN** the widget requests stop and the keyboard stop bridge has no active handler
- **THEN** the pending stop is durably enqueued before the widget receiver finishes the broadcast or reports queued stop.

### Requirement: Inline Dictation Commits Only To Current Input

Keyboard inline transcription SHALL commit text only to the input session that requested the transcription.

#### Scenario: Input changes before transcription finishes

- **WHEN** keyboard dictation starts in one input field
- **AND** the IME input generation changes before transcription finishes
- **THEN** the late transcript is not committed to the new input connection.

### Requirement: Sensitive Inputs Reject Dictation Commit

Keyboard dictation SHALL NOT insert transcribed text into sensitive input fields.

#### Scenario: Password field receives focus

- **WHEN** the keyboard starts input for a password or no-personalized-learning field
- **THEN** dictation commit is refused and any active keyboard recording is stopped through the normal keyboard stop path.

## Audit backlog (2026-05-25)

| Priority | Gap | Change |
|----------|-----|--------|
| _(none for pending stop — see archive/2026-05-25-recording-edge-case-races)_ | | |
| P4 | Checklist mode-controls reveal vs crossfade | `nav-search-playback-polish` |

See `openspec/changes/AUDIT_INDEX.md`.
