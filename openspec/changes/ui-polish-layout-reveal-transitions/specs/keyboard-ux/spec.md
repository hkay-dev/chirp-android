## ADDED Requirements

### Requirement: Keyboard mode controls reveal

The keyboard IME mode control row SHALL animate when dictation modes become available.

#### Scenario: Mode controls appear
- **WHEN** `showModeControls` becomes true after recording completes in the keyboard sheet
- **THEN** `ModeControlsRow` enters with `PushDownReveal`
- **AND** keyboard content column uses `animatePushDownLayout`

#### Scenario: Mode controls hide
- **WHEN** mode controls are not shown during recording or processing-only states
- **THEN** row exits with push-down hide transition without snapping waveform area height abruptly
