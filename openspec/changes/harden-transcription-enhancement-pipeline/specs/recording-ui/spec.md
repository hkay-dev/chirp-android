## ADDED Requirements

### Requirement: Pending Enhancement Recovery Is Visible

Recording UI SHALL expose recovery for `PENDING_ENHANCEMENT` wherever stuck transcription and enhancing recovery is exposed.

#### Scenario: Home list pending enhancement recovery

- **GIVEN** a recording is `PENDING_ENHANCEMENT`
- **AND** recovery diagnostics show missing or terminal enhancement ownership
- **WHEN** the user opens the recording action sheet
- **THEN** a recover stuck processing action SHALL be available
- **AND** the action SHALL invoke pending enhancement recovery.

#### Scenario: Processing Studio pending enhancement recovery

- **GIVEN** Processing Studio is open for a `PENDING_ENHANCEMENT` recording
- **AND** recovery diagnostics show missing or terminal enhancement ownership
- **WHEN** recovery affordances render
- **THEN** a pending enhancement recovery action SHALL be visible
- **AND** the action SHALL invoke pending enhancement recovery.

#### Scenario: Active enhancement ownership disables duplicate recovery

- **GIVEN** a recording is `PENDING_ENHANCEMENT`
- **AND** recovery diagnostics show active enhancement work
- **WHEN** Home or Processing Studio renders recovery actions
- **THEN** duplicate recovery SHALL be disabled or hidden
- **AND** the queued enhancement progress copy SHALL remain visible.
