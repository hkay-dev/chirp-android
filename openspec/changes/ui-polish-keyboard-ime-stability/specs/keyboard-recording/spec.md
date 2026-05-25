## ADDED Requirements

### Requirement: Keyboard recording elapsed time display

The keyboard voice recorder UI SHALL display elapsed recording duration during active capture.

#### Scenario: Timer starts with recording

- **WHEN** keyboard transitions to `Recording` state
- **THEN** elapsed time display SHALL begin at zero (or resume from zero on fresh capture)
- **AND** SHALL update at the standard timer tick interval defined by `ChirpMotion.TIMER_TICK_MS`

#### Scenario: Timer stops on record end

- **WHEN** user stops recording or recording is interrupted
- **THEN** elapsed time display SHALL be replaced by processing UI
- **AND** timer SHALL not continue incrementing during transcription

#### Scenario: Timer visible during waveform

- **WHEN** recording is active and waveform is displayed
- **THEN** elapsed time and waveform SHALL both be visible without overlapping illegibly

---

### Requirement: Keyboard stop action semantics

The keyboard stop control SHALL semantically represent stopping audio capture, consistent with other recording surfaces in the application.

#### Scenario: Stop ends capture and begins transcription

- **WHEN** user activates the stop control during keyboard recording
- **THEN** audio capture SHALL stop
- **AND** transcription pipeline SHALL begin
- **AND** the control SHALL have been labeled and iconographed as Stop (not Check)

#### Scenario: Stop control accessibility

- **WHEN** TalkBack focuses the stop control during recording
- **THEN** content description SHALL communicate "stop recording" semantics
- **AND** SHALL NOT communicate "confirm" or "done" semantics

## MODIFIED Requirements

### Requirement: Recording Synchronization

The keyboard SHALL capture audio from the first moment of recording with no data loss.

#### Scenario: No audio loss at recording start

- **WHEN** user taps record button
- **THEN** `collectSamples()` SHALL NOT begin reading until `AudioRecord.startRecording()` has completed
- **AND** no audio frames SHALL be missed between start and first sample read
- **AND** elapsed timer display SHALL begin in sync with successful recording start

#### Scenario: Recording too short

- **WHEN** user taps record and stop within 300 milliseconds
- **THEN** recording SHALL be discarded
- **AND** keyboard SHALL display error "Recording too short"
- **AND** no transcription SHALL be attempted

#### Scenario: Samples synchronized on stop

- **WHEN** `stop()` is called
- **THEN** all samples collected up to that point SHALL be returned
- **AND** no samples SHALL be lost or duplicated
- **AND** UI SHALL transition from Stop control and timer to processing indicator without an intermediate Check icon state
