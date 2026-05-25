## ADDED Requirements

### Requirement: Leaf settings screens use shared scaffolds

All navigable leaf settings screens SHALL use shared `core-ui` scaffolds instead of bespoke `Scaffold` + `LargeTopAppBar` implementations.

#### Scenario: Audio settings scaffold

- **WHEN** user navigates to Audio Settings from the Settings hub
- **THEN** the screen is wrapped in `ChirpSettingsDetailScaffold`
- **AND** no local `LargeTopAppBar` or duplicate scroll-behavior wiring remains

#### Scenario: Keyboard settings scaffold

- **WHEN** user navigates to Keyboard Settings
- **THEN** the screen uses `ChirpSettingsDetailScaffold` with the keyboard settings title

#### Scenario: Integration settings scaffolds

- **WHEN** user navigates to LLM, Transcription, or Obsidian settings
- **THEN** each screen uses `ChirpSettingsDetailScaffold`
- **AND** screen-specific content is passed via the scaffold content lambda only

#### Scenario: Developer menu scaffold

- **WHEN** user opens the Developer Menu from Settings (debug builds)
- **THEN** the screen uses `ChirpSettingsDetailScaffold` with snackbar support
- **AND** retains existing debug functionality

#### Scenario: Settings hub unchanged

- **WHEN** user views the main Settings tab hub
- **THEN** `ChirpSettingsHubScaffold` continues to be used
- **AND** hub navigation targets remain History → Notes → Settings order per existing requirements
