## MODIFIED Requirements

### Requirement: Recording Playback

The system SHALL provide playback controls for completed recordings using a Media3 session-backed service connected via MediaController binding. Prepare-only operations (load media metadata without playing) SHALL connect without invoking `startForegroundService()` unless the playback service explicitly promotes itself to foreground for active background playback.

#### Scenario: Play recording
- **WHEN** user taps play on a recording
- **THEN** audio playback begins from current position
- **AND** waveform shows playback progress

#### Scenario: Seek recording
- **WHEN** user drags the seek bar
- **THEN** playback position updates to selected time
- **AND** waveform reflects new position

#### Scenario: Prepare recording for studio or mini player
- **WHEN** the app prepares playback for a completed recording without starting audio output
- **THEN** `RecordingPlaybackController` SHALL connect to `RecordingPlaybackService` via MediaController (bind)
- **AND** the app SHALL NOT call `startForegroundService()` solely for prepare
- **AND** duration and title metadata SHALL be available to the UI when the media item is loaded

#### Scenario: Prepare does not trigger foreground service timeout
- **WHEN** prepare completes without calling `play()`
- **THEN** the system SHALL NOT require `RecordingPlaybackService` to call `startForeground()` within the foreground service start window
- **AND** no `ForegroundServiceDidNotStartInTimeException` SHALL be thrown

### Requirement: Recording Service Coordination

The system SHALL use a single RecordingService for all recording sources. Foreground service promotion with notification SHALL apply to active audio capture in RecordingService only, not to playback preparation in RecordingPlaybackService.

#### Scenario: Unified recording service
- **WHEN** recording starts from app, keyboard, or widget
- **THEN** all use the same RecordingService
- **AND** service tracks the recording source
- **AND** only one recording can be active at a time

#### Scenario: Foreground service notification during capture
- **WHEN** recording is in progress
- **THEN** RecordingService SHALL show a foreground service notification
- **AND** notification indicates recording source
- **AND** tapping notification opens active recording screen

#### Scenario: Playback service is separate from recording foreground
- **WHEN** the user prepares or plays a completed recording
- **THEN** RecordingPlaybackService SHALL handle playback session lifecycle
- **AND** RecordingService foreground notification rules SHALL NOT be conflated with playback connection
- **AND** prepare-only playback SHALL NOT force RecordingPlaybackService into foreground without an explicit playback policy decision

#### Scenario: Recording service releases shared listeners on destroy
- **WHEN** RecordingService is destroyed after a recording session
- **THEN** shared audio infrastructure callbacks registered by the service SHALL be cleared
- **AND** a subsequent recording session SHALL register fresh callbacks on a new service instance
