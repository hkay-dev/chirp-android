## ADDED Requirements

### Requirement: Non-Blocking Playback Preparation
Prepare-only playback operations used by home and studio surfaces SHALL connect to the playback engine without blocking the UI thread or triggering foreground service timeout crashes.

#### Scenario: Studio screen loads playback metadata without ANR
- **GIVEN** the user opens Processing Studio for a recording with a valid audio file
- **WHEN** the screen initializes and calls prepare on the shared playback controller
- **THEN** duration and title SHALL populate the mini player or transport chrome
- **AND** the UI SHALL remain interactive within one frame budget (no ANR dialog)
- **AND** no foreground service start timeout SHALL occur

#### Scenario: Home list preload does not freeze UI
- **GIVEN** the user triggers playback prepare from the home recording list or inline player
- **WHEN** the controller connects to `RecordingPlaybackService`
- **THEN** list scrolling SHALL remain smooth
- **AND** prepare SHALL complete or surface an error without starting a foreground service prematurely

#### Scenario: Prepare failure shows error without crash
- **GIVEN** the audio file path is missing or invalid
- **WHEN** prepare is invoked from studio or home
- **THEN** the controller SHALL surface an error state (e.g., "Audio file not found")
- **AND** the app SHALL NOT crash or ANR
- **AND** the user MAY retry after fixing storage issues

## MODIFIED Requirements

### Requirement: Audio Player State Animation

Sticky audio player SHALL animate all state changes for polished interaction, and playback controller connection SHALL NOT degrade UI responsiveness during prepare-only sessions.

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

#### Scenario: Prepare state does not block animations
- **GIVEN** the player is visible during a prepare-only session (play not yet started)
- **WHEN** duration metadata loads asynchronously
- **THEN** loading indicators SHALL animate per the loading state requirement
- **AND** the prepare connection SHALL NOT cause jank or ANR that prevents animations from running
