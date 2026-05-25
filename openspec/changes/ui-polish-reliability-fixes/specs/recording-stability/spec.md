## ADDED Requirements

### Requirement: Recording Service Listener Cleanup on Destroy
When `RecordingService` is destroyed, the system SHALL release all callbacks registered with shared audio components so that no closure referencing the service instance remains reachable.

#### Scenario: Service destroy clears device-lost listener
- **GIVEN** `RecordingService` registered an `onActiveDeviceLost` listener during `onCreate()`
- **WHEN** `RecordingService.onDestroy()` completes
- **THEN** `AudioInputDeviceSelector` SHALL have no active device-lost listener registered by that service
- **AND** a subsequent audio input device removal SHALL NOT invoke recording stop logic from the destroyed service

#### Scenario: Service destroy clears audio focus callback
- **GIVEN** `RecordingService` assigned `audioFocusManager.onFocusLost` during `onCreate()`
- **WHEN** `RecordingService.onDestroy()` completes
- **THEN** `audioFocusManager.onFocusLost` SHALL be null
- **AND** a subsequent system audio focus loss event SHALL NOT invoke pause or stop on the destroyed service

#### Scenario: Abnormal termination still clears callbacks
- **GIVEN** a recording session ends due to process kill or crash recovery path
- **WHEN** a new `RecordingService` instance is created after restart
- **THEN** only the new instance's listeners SHALL be active
- **AND** stale listeners from prior instances SHALL NOT persist on shared singletons

### Requirement: Active Device Listener Cleared With Session Reset
`AudioInputDeviceSelector.clearActiveDevice()` SHALL reset all session-scoped tracking state including any registered device-lost callback, ensuring normal recording stop paths do not leave listeners attached.

#### Scenario: Stop recording clears device listener via clearActiveDevice
- **GIVEN** a recording session registered `onActiveDeviceLost` on `AudioInputDeviceSelector`
- **WHEN** recording stops successfully and the service calls `clearActiveDevice()`
- **THEN** `onActiveDeviceLost` SHALL be null
- **AND** `activeDeviceId` and `activeDeviceLabel` SHALL be cleared

#### Scenario: Cancel recording clears device listener
- **GIVEN** a recording session is active with a device-lost listener registered
- **WHEN** the user cancels the recording and cleanup runs
- **THEN** `clearActiveDevice()` SHALL have been invoked
- **AND** no device-lost listener SHALL remain registered

#### Scenario: Mic disconnect during active recording still handled
- **GIVEN** a recording is in progress with `onActiveDeviceLost` registered
- **WHEN** the active input device is removed before stop completes
- **THEN** the listener SHALL still invoke the registered handler
- **AND** recording SHALL stop with user-visible error feedback
- **AND** cleanup SHALL subsequently clear the listener when the session ends

### Requirement: Singleton Audio Callback Single-Owner Contract
Components that register callbacks on `@Singleton` audio infrastructure (`AudioInputDeviceSelector`, shared focus managers) SHALL follow a single-owner contract: register on lifecycle start, clear on lifecycle end, and never assume implicit cleanup from session helpers alone.

#### Scenario: Only one device-lost registrant at a time
- **GIVEN** `RecordingService` is the active recording coordinator
- **WHEN** it registers `onActiveDeviceLost`
- **THEN** no other component SHALL register a competing device-lost handler without clearing the prior registration
- **AND** documentation in code SHALL identify `RecordingService` as the expected registrant during capture

#### Scenario: Re-created service registers fresh listener
- **GIVEN** a prior `RecordingService` instance was destroyed and cleared its listeners
- **WHEN** a new recording starts and a new `RecordingService` instance is created
- **THEN** `onCreate()` SHALL register a new device-lost listener
- **AND** the new listener SHALL reference the new service instance's scope and state

### Requirement: Prepare-Only Playback Does Not Require Foreground Service Start
Connecting to `RecordingPlaybackService` for metadata preload or paused preparation SHALL NOT use `startForegroundService()` unless the playback service explicitly enters a foreground playback policy that calls `startForeground()`.

#### Scenario: Studio opens without playback ANR
- **GIVEN** the user navigates to Processing Studio for a completed recording
- **WHEN** the UI calls `RecordingPlaybackController.prepare()` to load duration and show the mini player
- **THEN** the app SHALL NOT call `startForegroundService()` for `RecordingPlaybackService` solely to establish the MediaController connection
- **AND** the main thread SHALL remain responsive (no ANR within 5 seconds)

#### Scenario: Prepare without play does not promote playback service
- **GIVEN** playback is prepared but `play()` has not been invoked
- **WHEN** five seconds elapse after controller connection
- **THEN** the system SHALL NOT throw `ForegroundServiceDidNotStartInTimeException`
- **AND** no orphan foreground service start timeout SHALL occur

#### Scenario: Play after prepare still works
- **GIVEN** playback was prepared via bind-only MediaController connection
- **WHEN** the user taps play in the mini player or studio transport
- **THEN** audio playback SHALL begin normally
- **AND** seek and pause controls SHALL function as before the reliability fix
