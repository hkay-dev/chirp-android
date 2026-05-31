# recording-ui Specification

## Purpose
TBD - created by archiving change improve-ui-smoothness-120hz. Update Purpose after archive.
## Requirements
### Requirement: Smooth Recording State Transitions

Recording screen state changes SHALL animate all visual elements simultaneously with coordinated timing.

#### Scenario: Idle to recording transition
- **WHEN** recording starts
- **THEN** main action button color animates to red over 400ms
- **AND** status text crossfades to "Recording" over 250ms
- **AND** secondary buttons fade in with slide over 300ms
- **AND** glow background fades in over 500ms

#### Scenario: Recording to saving transition
- **WHEN** recording stops
- **THEN** status text crossfades to "Saving..." over 250ms
- **AND** button state transitions smoothly (no blink)
- **AND** glow background crossfades from recording color to idle

---

### Requirement: Unified Glow Background Animation

Recording glow background SHALL use a single animated color (not dual overlapping glows) to prevent visual artifacts during transitions.

#### Scenario: Recording glow is solid
- **WHEN** recording is active
- **THEN** a single red radial glow is visible
- **AND** no color blending artifacts occur

#### Scenario: Glow color transitions smoothly
- **WHEN** state changes from recording to paused
- **THEN** glow color animates from red to amber over 500ms
- **AND** at no point are both colors visible simultaneously

#### Scenario: Glow fades to transparent
- **WHEN** recording stops and transitions to idle
- **THEN** glow alpha animates to 0 over 500ms
- **AND** brush objects are cached (not recreated per frame)

---

### Requirement: Synchronized Button Transitions

Main action button icon and color transitions SHALL use identical timing to prevent visual desync.

#### Scenario: Icon and color change together
- **WHEN** button state changes (e.g., recording to idle)
- **THEN** icon crossfade duration matches color animation duration (both 300ms)
- **AND** no moment exists where icon contrast is poor against background

---

### Requirement: Efficient Timer Updates

Recording timer SHALL update at a frequency appropriate for human perception to reduce recomposition load.

#### Scenario: Timer updates at readable rate
- **WHEN** recording is active
- **THEN** timer display updates every 500ms (not every 100ms)
- **AND** timer text change does not cause visible frame drops

---

### Requirement: Detail Screen Content Animation

Recording detail screen content changes SHALL animate smoothly without instant content swaps.

#### Scenario: Transcript appears with animation
- **WHEN** transcription completes and text is available
- **THEN** transcript section fades in with slight scale (0.95x to 1.0x) over 300ms

#### Scenario: Summary section animates open
- **WHEN** AI summary is generated
- **THEN** summary card expands vertically with fade over 300ms
- **AND** content below shifts smoothly (animateContentSize)

#### Scenario: Loading state animates
- **WHEN** transcription is in progress
- **THEN** loading indicator fades in with slide over 200ms
- **AND** progress bar animates smoothly (not discrete jumps)

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

### Requirement: Full-Screen Recording Interface

The app SHALL provide a dedicated full-screen recording interface that users navigate to when starting a new recording.

#### Scenario: User initiates recording from home screen
- **WHEN** user taps the record FAB on the home screen
- **THEN** the app navigates to the full-screen RecordScreen
- **AND** recording starts automatically

#### Scenario: User completes recording
- **WHEN** user taps the Done button during an active recording
- **THEN** the recording stop is requested
- **AND** transcription is queued after finalize completes
- **AND** user is navigated to Processing Studio **immediately** using `activeRecordingId` when available
- **AND** Processing Studio shows the finalizing (“Stitching your recording together”) progress state while stop completes
- **AND** exactly one navigation occurs per stop (`hasNavigatedToComplete` dedupe; `LaunchedEffect(lastCompletedRecordingId)` is fallback only)

#### Scenario: User cancels recording
- **WHEN** user taps the Cancel button during an active recording
- **THEN** a confirmation dialog is shown
- **AND** if confirmed, the recording is discarded
- **AND** user is returned to the home screen

### Requirement: Real-Time Audio Waveform

The RecordScreen SHALL display a real-time audio waveform visualization during recording.

#### Scenario: Waveform displays audio levels
- **WHEN** recording is active
- **THEN** the waveform displays 50 vertical bars
- **AND** each bar height corresponds to current audio amplitude
- **AND** bars animate smoothly using spring physics

#### Scenario: Waveform shows idle state
- **WHEN** recording is idle or paused
- **THEN** the waveform displays a subtle dotted line
- **AND** the dotted line fades out when recording starts

### Requirement: Three-State Recording System

The recording interface SHALL support three distinct states: Idle, Recording, and Paused.

#### Scenario: Idle state appearance
- **WHEN** recording has not started
- **THEN** the main button shows a microphone icon
- **AND** the button uses the primary container color
- **AND** no pulsing animation is active

#### Scenario: Recording state appearance
- **WHEN** recording is active
- **THEN** the main button shows a stop/pause icon
- **AND** the button uses the error (red) color
- **AND** the button pulses at 900ms intervals
- **AND** the background shows a red radial glow

#### Scenario: Paused state appearance
- **WHEN** recording is paused
- **THEN** the main button shows a record icon (to resume)
- **AND** the button uses the error (red) color without pulsing
- **AND** the background shows an amber radial glow

### Requirement: Recording Timer Display

The RecordScreen SHALL display a prominent timer showing elapsed recording time.

#### Scenario: Timer updates during recording
- **WHEN** recording is active
- **THEN** the timer displays elapsed time in MM:SS format
- **AND** the timer updates every second
- **AND** the timer uses a large monospace font (72sp)

#### Scenario: Timer shows hours for long recordings
- **WHEN** recording exceeds 60 minutes
- **THEN** the timer displays in H:MM:SS format

### Requirement: Secondary Control Buttons

The RecordScreen SHALL display secondary control buttons when recording is active or paused.

#### Scenario: Secondary buttons appear when recording starts
- **WHEN** recording transitions from Idle to Recording
- **THEN** secondary buttons (Done, Cancel, Restart) animate into view
- **AND** buttons slide up with fade-in animation

#### Scenario: Done button saves and transcribes
- **WHEN** user taps the Done button
- **THEN** the recording is saved to the database
- **AND** a transcription work request is enqueued

#### Scenario: Cancel button discards recording
- **WHEN** user taps the Cancel button
- **THEN** a confirmation dialog appears
- **AND** if confirmed, the audio file is deleted
- **AND** no database record is created

#### Scenario: Restart button clears and restarts
- **WHEN** user taps the Restart button
- **THEN** the current recording is discarded
- **AND** a new recording session begins immediately

### Requirement: Back Gesture Handling

The RecordScreen SHALL handle back gestures safely during active recordings.

#### Scenario: Back gesture during recording
- **WHEN** user performs a back gesture while recording is active or paused
- **THEN** a confirmation dialog appears with Save, Discard, and Browse home actions
- **AND** Browse home returns to Home without stopping capture
- **AND** recording continues in the background until Save or Discard

#### Scenario: Back gesture when idle
- **WHEN** user performs a back gesture while in Idle state
- **THEN** user is returned to the home screen with no dialog

### Requirement: Live Capture Home Row

While APP capture is active, Home SHALL surface the in-progress recording as a distinct list row.

#### Scenario: Live row visible during capture
- **WHEN** the user browses Home during an active APP recording
- **THEN** the matching `RECORDING` row is visible on Home
- **AND** the row shows a pulsing red Recording banner with live elapsed duration
- **AND** playback controls are hidden for that row

#### Scenario: Live row returns to RecordScreen
- **WHEN** user taps the live capture row on Home
- **THEN** the app navigates to RecordScreen with `autoStart=false`
- **AND** the existing capture session resumes presentation without starting a new recording

#### Scenario: Record FAB resumes active capture
- **WHEN** user taps the record FAB while APP capture is already active
- **THEN** the app navigates to RecordScreen with `autoStart=false`

### Requirement: Audio Shares Use Canonical MIME Types

Audio share intents SHALL derive their MIME type from the recording file format.

#### Scenario: Sharing an M4A recording

- **WHEN** the user shares an `.m4a` recording
- **THEN** the share intent type is `audio/mp4`.

#### Scenario: Sharing MP3 or WAV recordings

- **WHEN** the user shares `.mp3` or `.wav` recordings
- **THEN** the share intent type is `audio/mpeg` or `audio/wav` respectively.
- **AND** no AlreadyRecording error is shown for APP-origin capture

#### Scenario: Finalize row is not live capture
- **WHEN** a `RECORDING` row is finalizing in the background queue
- **THEN** Home shows the Stitching progress banner (not live capture styling)

### Requirement: Background Glow Effects

The RecordScreen SHALL display ambient background effects that reflect recording state.

#### Scenario: Recording glow effect
- **WHEN** recording is active
- **THEN** a red radial gradient glow appears behind the content
- **AND** the glow fades in over 600ms

#### Scenario: Paused glow effect
- **WHEN** recording is paused
- **THEN** an amber/tertiary radial gradient glow appears
- **AND** the glow transitions smoothly from the recording glow

#### Scenario: Idle has no glow
- **WHEN** recording is idle
- **THEN** no background glow effect is visible

### Requirement: Processing Studio Invalid Recording ID

Processing Studio SHALL reject malformed recording identifiers before loading data and SHALL provide a visible exit path.

#### Scenario: Deep link with invalid UUID

- **GIVEN** navigation targets Processing Studio with a non-UUID recording id argument
- **WHEN** the screen opens
- **THEN** the user SHALL see an error message explaining the recording could not be loaded
- **AND** a back or Home action SHALL be available
- **AND** an infinite loading indicator SHALL NOT be shown

### Requirement: Processing Studio Missing Recording

When the requested recording no longer exists, Processing Studio SHALL exit the loading state and allow navigation away.

#### Scenario: Recording deleted while Studio open

- **GIVEN** Processing Studio is open for recordingId R
- **WHEN** row R is deleted or never existed after load completes
- **THEN** the UI SHALL show a not-found state
- **AND** observation SHALL stop
- **AND** the user SHALL be able to return to Home

### Requirement: Home Import Studio Navigation

Successful audio import from Home SHALL navigate to Processing Studio for the new recording, matching share-import behavior.

#### Scenario: User imports file from Home

- **GIVEN** the user selects import on Home
- **WHEN** import succeeds and a recording row is created
- **THEN** the app SHALL navigate to Processing Studio for that recording id
- **AND** transcription progress SHALL be visible

### Requirement: Terminal Failure Presentation

Processing Studio SHALL present a single primary error surface for recordings in `FAILED` status.

#### Scenario: Transcription failed

- **GIVEN** recording status is `FAILED`
- **WHEN** Studio renders the transcription tab
- **THEN** exactly one consolidated error message SHALL be shown
- **AND** duplicate stacked error banners or recovery blocks SHALL NOT appear

### Requirement: Processing Studio Navigation Stack

Navigation from Home to Processing Studio for the same recording SHALL reuse the existing Studio destination when already on the back stack.

#### Scenario: User taps same recording twice on Home

- **GIVEN** Processing Studio is already open for recording R
- **WHEN** the user taps recording R again on Home
- **THEN** navigation SHALL use single-top semantics
- **AND** the back stack SHALL NOT contain duplicate Studio entries for R

### Requirement: Mini Player Studio Context

When the user opens Processing Studio for a recording different from the mini player's current item, playback SHALL not continue the previous recording unexpectedly.

#### Scenario: Mini player active then open different Studio

- **GIVEN** mini player is playing recording A
- **WHEN** the user opens Processing Studio for recording B
- **THEN** playback of A SHALL pause or the mini player SHALL switch context to B per product policy
- **AND** audio from A SHALL NOT play while viewing B without explicit user action

## Audit backlog (2026-05-25)

| Priority | Gap | Change |
|----------|-----|--------|
| P3–P4 | Matrix drift, dead wrappers, coverage gaps | `docs-test-hygiene` |

See `openspec/changes/AUDIT_INDEX.md`.
