## MODIFIED Requirements

### Requirement: Transcription Progress

The system SHALL show transcription progress to users in notifications and in the Processing Studio UI, reflecting the active pipeline phase with distinct presentation.

#### Scenario: Show progress notification
- **WHEN** transcription is in progress
- **THEN** a notification shows current progress
- **AND** includes the recording title if available

#### Scenario: Show progress in UI
- **WHEN** user views a recording being transcribed in Processing Studio
- **THEN** the detail screen shows transcription progress in the processing header (compact morphing banner)
- **AND** the Transcript tab body shows **skeleton lines only** while `transcriptionProgressKind()` is non-null — not a second full progress panel
- **AND** progress copy reflects the active phase (finalizing, transcribing, or enhancing)

#### Scenario: Phase-specific progress presentation
- **WHEN** recording status is `RECORDING` (finalizing), `PENDING_TRANSCRIPTION`/`TRANSCRIBING`, or `ENHANCING`/`PENDING_ENHANCEMENT`
- **THEN** progress UI displays the corresponding `TranscriptionProgressKind` icon and title/subtitle strings
- **AND** phase transitions crossfade without blank intermediate states

## ADDED Requirements

### Requirement: Transcript tab processing empty-state prevention

While transcription pipeline progress is active, the Transcript tab SHALL NOT render an empty content region.

#### Scenario: Processing with known phase
- **WHEN** user is on the Transcript tab
- **AND** `transcriptionProgressKind()` returns a non-null kind
- **AND** transcript content is not yet available for display
- **THEN** skeleton placeholder lines fill the tab body
- **AND** compact progress appears **only** in the studio header (not duplicated in the tab body)

#### Scenario: Processing before phase kind resolves
- **WHEN** transcript tab is waiting during initial studio load with processing expected
- **AND** progress kind is not yet available
- **THEN** skeleton placeholder lines appear in the tab body
- **AND** the tab does not show a blank weighted container

#### Scenario: Processing ends
- **WHEN** transcription pipeline completes and transcript text is available
- **THEN** processing fallback exits via animated visibility transition
- **AND** transcript content or empty-completed message replaces the fallback
