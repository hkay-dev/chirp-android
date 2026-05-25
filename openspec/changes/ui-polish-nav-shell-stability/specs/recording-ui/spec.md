## ADDED Requirements

### Requirement: Session Recovery Dialog Presentation

Interrupted recording recovery prompts on Home and Record screens SHALL use animated dialog presentation consistent with other recording confirmations.

#### Scenario: Recovery dialog animates in on Home
- **WHEN** a recoverable session is detected and the recovery prompt is shown on Home
- **THEN** `AnimatedAlertDialog` presents the recover/keep/discard actions
- **AND** the dialog enters with fade and scale animation

#### Scenario: Recovery dialog animates in on Record
- **WHEN** a recoverable session is detected and the recovery prompt is shown on Record
- **THEN** `AnimatedAlertDialog` presents the recover/keep/discard actions
- **AND** presentation matches Home recovery dialog behavior

---

## MODIFIED Requirements

### Requirement: Full-Screen Recording Interface

The app SHALL provide a dedicated full-screen recording interface that users navigate to when starting a new recording.

#### Scenario: User initiates recording from home screen
- **WHEN** user taps the record FAB on the home screen
- **THEN** the app navigates to the full-screen RecordScreen
- **AND** recording starts automatically

#### Scenario: User completes recording
- **WHEN** user taps the Done button during an active recording
- **THEN** the recording is saved
- **AND** transcription is queued
- **AND** user is navigated to Processing Studio **immediately** using `activeRecordingId` when available
- **AND** exactly one navigation occurs per stop (`hasNavigatedToComplete` dedupe; `LaunchedEffect(lastCompletedRecordingId)` is fallback only)

#### Scenario: User cancels recording
- **WHEN** user taps the Cancel button during an active recording
- **THEN** a confirmation dialog is shown
- **AND** if confirmed, the recording is discarded
- **AND** user is returned to the home screen

---

### Requirement: Audio Player State Animation

Sticky audio player SHALL animate all state changes for polished interaction.

#### Scenario: Player slides in from bottom
- **WHEN** audio player becomes visible
- **THEN** player slides in from bottom edge over 300ms with fade

#### Scenario: Play/pause icon crossfades
- **WHEN** playback state toggles
- **THEN** icon crossfades between play and pause over 200ms

#### Scenario: Progress slider animates
- **WHEN** playback position updates
- **THEN** slider thumb position animates smoothly (100ms tween)
- **AND** no visible jumping between positions

#### Scenario: Skip buttons have press feedback
- **WHEN** user presses skip forward/backward
- **THEN** icon scales to 0.85x during press with spring return

#### Scenario: Seek track animates in when duration known
- **WHEN** mini player duration becomes available (`durationMs > 0`) and no error is present
- **THEN** `MiniPlayerSeekTrack` enters with vertical expand and fade over 300ms
- **AND** the mini player bar height adjusts smoothly

#### Scenario: Seek track animates out on stop or error
- **WHEN** playback stops, duration resets to zero, or an error is shown
- **THEN** `MiniPlayerSeekTrack` exits with vertical shrink and fade over 260ms
- **AND** controls row remains visible during loading/active states without seek track pop-in
