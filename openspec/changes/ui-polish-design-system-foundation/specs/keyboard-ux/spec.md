## ADDED Requirements

### Requirement: Keyboard theme uses ChirpTheme

The keyboard IME Compose theme SHALL delegate to `ChirpTheme` so typography, shapes, and color scheme match the main application design system.

#### Scenario: KeyboardTheme alias

- **WHEN** `KeyboardTheme` composable is invoked in the IME
- **THEN** it delegates to `ChirpTheme` with equivalent `darkTheme` and `dynamicColor` parameters
- **AND** does not instantiate a separate `MaterialTheme` with default `Typography()`

#### Scenario: Waveform styling under unified theme

- **WHEN** waveform visualization is displayed during recording
- **THEN** it uses `MaterialTheme.colorScheme` from `ChirpTheme`
- **AND** adapts to light/dark theme consistently with app surfaces

#### Scenario: No duplicate theme maintenance

- **WHEN** `ChirpTypography` or `ChirpShapes` tokens are updated in `core-ui`
- **THEN** keyboard UI picks up changes automatically via `KeyboardTheme` delegation
- **AND** no parallel theme object requires manual sync
