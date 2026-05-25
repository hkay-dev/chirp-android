## MODIFIED Requirements

### Requirement: Transcription Progress

The system SHALL show transcription progress to users.

#### Scenario: Show progress notification

- **WHEN** transcription is in progress
- **THEN** a notification shows current progress
- **AND** includes the recording title if available

#### Scenario: Show progress in UI

- **WHEN** user views a recording being transcribed
- **THEN** the detail screen shows transcription progress
- **AND** the home list shows compact transcription progress on the corresponding list item when the recording is in a pipeline status

#### Scenario: Consistent progress phases across surfaces

- **WHEN** transcription progress is shown on the home list or Processing Studio
- **THEN** both surfaces derive phase (Finalizing, Transcribing, Enhancing) from the same shared status-to-phase mapping
- **AND** progress title and subtitle strings match for the same phase

#### Scenario: Compact progress on home list

- **WHEN** user views the home list while a recording is PENDING_TRANSCRIPTION, TRANSCRIBING, PENDING_ENHANCEMENT, or ENHANCING
- **THEN** the list item displays compact morphing transcription progress below metadata pills
- **AND** the progress affordance is visually consistent with the studio header compact banner
