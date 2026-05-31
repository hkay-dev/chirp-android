## MODIFIED Requirements

### Requirement: Keyboard Recording Persistence

The keyboard SHALL save recordings durably to the local database when the "Save keyboard recordings" setting is enabled, and persistence SHALL complete or reach a terminal failure before keyboard recording completion is reported.

#### Scenario: Save keyboard recording enabled

- **WHEN** user has enabled "Save keyboard recordings"
- **AND** user completes a keyboard voice recording
- **THEN** PCM samples SHALL be encoded to the configured audio format
- **AND** the audio file SHALL be saved to `filesDir/recordings/`
- **AND** a `Recording` entity SHALL be created with `source = RecordingSource.KEYBOARD`
- **AND** a `Transcript` entity SHALL be created when transcript text exists
- **AND** `InlineCapturePersistence.persist()` SHALL return only after the file and database writes complete or fail.

#### Scenario: Save keyboard recording disabled

- **WHEN** user has disabled "Save keyboard recordings"
- **AND** user completes a keyboard voice recording
- **THEN** no audio file SHALL be created
- **AND** no `Recording` entity SHALL be created
- **AND** the persistence call SHALL return only after this decision has been read from durable preferences.

#### Scenario: Encoding or database failure

- **WHEN** keyboard capture persistence cannot encode audio or write the database row
- **THEN** the failure SHALL be logged
- **AND** transcription commit to the input field SHALL NOT be rolled back
- **AND** callers SHALL NOT treat a background fire-and-forget save as still pending.

#### Scenario: IME teardown after transcription

- **WHEN** keyboard transcription finishes and the IME begins teardown
- **THEN** any enabled local recording persistence already awaited by `persist()` SHALL have completed or reached terminal failure
- **AND** cancelling the IME persistence scope SHALL NOT drop an untracked local save.
