## MODIFIED Requirements

### Requirement: Transcription Progress

The system SHALL show transcription progress to users in notifications and in the Processing Studio UI, reflecting the active pipeline phase with distinct presentation.

#### Scenario: Show progress in UI
- **WHEN** user views a recording being transcribed in Processing Studio
- **THEN** compact progress appears in the processing header via `PushDownReveal`
- **AND** Transcript tab body shows skeleton lines only (not duplicate progress panel)
- **AND** transcript body state changes use `AnimatedContent` with size spring
